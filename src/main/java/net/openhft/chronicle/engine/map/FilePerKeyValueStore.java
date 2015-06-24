/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.engine.map;

import com.sun.nio.file.SensitivityWatchEventModifier;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.bytes.BytesUtil;
import net.openhft.chronicle.bytes.IORuntimeException;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.map.StringBytesStoreKeyValueStore;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.SubscriptionConsumer;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;
import java.util.AbstractMap.SimpleEntry;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static net.openhft.chronicle.core.Jvm.pause;
import static net.openhft.chronicle.engine.api.EngineReplication.ReplicationEntry;

/**
 * A {@link Map} implementation that stores each entry as a file in a
 * directory. The <code>key</code> is the file fullName and the <code>value</code>
 * is the contents of the file.
 * <p>
 * The class is effectively an abstraction over a directory in the file system.
 * Therefore when the underlying files are changed an event will be fired to those
 * registered for notifications.
 * <p>
 * Updates will be fired every time the file is saved but will be suppressed
 * if the value has not changed.  To avoid temporary files (e.g. if edited in vi)
 * being included in the map, any file starting with a '.' will be ignored.
 * <p>
 * Note the {@link WatchService} is extremely OS dependant.  Mas OSX registers
 * very few events if they are done quickly and there is a significant delay
 * between the event and the event being triggered.
 */
public class FilePerKeyValueStore implements StringBytesStoreKeyValueStore, Closeable,
        ChronicleMapBackedEngineReplication.ChangeApplier<FilePerKeyValueStore>,
        ChronicleMapBackedEngineReplication.GetValue<FilePerKeyValueStore> {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilePerKeyValueStore.class);
    private final Path dirPath;
    //Use BytesStore so that it can be shared safely between threads
    private final Map<File, FileRecord<BytesStore>> lastFileRecordMap = new ConcurrentHashMap<>();

    @NotNull
    private final Thread fileFpmWatcher;
    private final RawKVSSubscription<String, Bytes, BytesStore> subscriptions;
    private final Asset asset;
    private volatile boolean closed = false;

    public FilePerKeyValueStore(@NotNull RequestContext context, @NotNull Asset asset) throws IORuntimeException {
        this(context, asset, context.type(), context.basePath(), context.name());
        asset.registerView(StringBytesStoreKeyValueStore.class, this);
    }

    FilePerKeyValueStore(RequestContext context, Asset asset, Class type, String basePath, String name) {
        this.asset = asset;
        assert type == String.class;

        String first = basePath;
        String dirName = first == null ? name : first + "/" + name;
        this.dirPath = Paths.get(dirName);
        WatchService watcher;
        try {
            Files.createDirectories(dirPath);
            watcher = FileSystems.getDefault().newWatchService();
            dirPath.register(watcher, new Kind[]{
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY},
                    SensitivityWatchEventModifier.HIGH
            );
        } catch (IOException e) {
            throw new IORuntimeException(e);
        }

        fileFpmWatcher = new Thread(new FPMWatcher(watcher), dirName + "-watcher");
        fileFpmWatcher.setDaemon(true);
        fileFpmWatcher.start();
        subscriptions = asset.acquireView(RawKVSSubscription.class, context);
        subscriptions.setKvStore(this);
    }

    @NotNull
    @Override
    public RawKVSSubscription<String, Bytes, BytesStore> subscription(boolean createIfAbsent) {
        return subscriptions;
    }

    @Override
    public long longSize() {
        return getFiles().count();
    }

    @Nullable
    @Override
    public BytesStore getUsing(String key, Bytes value) {
        Path path = dirPath.resolve(key);
        return getFileContents(path, value);
    }

    @Override
    public void keysFor(int segment, @NotNull SubscriptionConsumer<String> stringConsumer) {
        try {
            keysFor0(stringConsumer);
        } catch (InvalidSubscriberException ise) {
            // ignored
        }
    }

    void keysFor0(@NotNull SubscriptionConsumer<String> stringConsumer) throws InvalidSubscriberException {
        getFiles().forEach(p -> {
            try {
                stringConsumer.accept(p.getFileName().toString());
            } catch (InvalidSubscriberException e) {
                throw Jvm.rethrow(e);
            }
        });
    }

    @Override
    public void entriesFor(int segment, @NotNull SubscriptionConsumer<MapEvent<String, BytesStore>> kvConsumer) throws InvalidSubscriberException {
        try {
            entriesFor0(kvConsumer);
        } catch (InvalidSubscriberException ise) {
            // ignored
        }
    }

    void entriesFor0(@NotNull SubscriptionConsumer<MapEvent<String, BytesStore>> kvConsumer) throws InvalidSubscriberException {
        getFiles().map(p -> InsertedEvent.of(asset.fullName(), p.getFileName().toString(), getFileContents(p, null)))
                .forEach(e -> {
                    try {
                        // in case the file has been deleted in the meantime.
                        if (e != null)
                            kvConsumer.accept(e);
                    } catch (InvalidSubscriberException ise) {
                        throw Jvm.rethrow(ise);
                    }
                });
    }

    @Override
    public Iterator<String> keySetIterator() {
        return getFiles().map(p -> p.getFileName().toString()).iterator();
    }

    @Override
    public Iterator<Map.Entry<String, BytesStore>> entrySetIterator() {
        return getEntryStream().iterator();
    }

    public Stream<Map.Entry<String, BytesStore>> getEntryStream() {
        return getFiles()
                .map(p -> (Map.Entry<String, BytesStore>) new SimpleEntry<>(p.getFileName().toString(), getFileContents(p, null)));
    }

    @Override
    public boolean put(String key, @NotNull BytesStore value) {
        if (closed) throw new IllegalStateException("closed");
        Path path = dirPath.resolve(key);
        FileRecord fr = lastFileRecordMap.get(path.toFile());
        writeToFile(path, value);
        if (fr != null) fr.valid = false;
        return fr != null;
    }

    @Nullable
    @Override
    public BytesStore getAndPut(String key, @NotNull BytesStore value) {
        if (closed) throw new IllegalStateException("closed");
        Path path = dirPath.resolve(key);
        FileRecord fr = lastFileRecordMap.get(path.toFile());
        BytesStore existingValue = getFileContents(path, null);
        writeToFile(path, value);
        if (fr != null) fr.valid = false;
        return existingValue == null ? null : existingValue;
    }

    @Nullable
    @Override
    public BytesStore getAndRemove(String key) {
        if (closed) throw new IllegalStateException("closed");
        BytesStore existing = get(key);
        if (existing != null) {
            deleteFile(dirPath.resolve(key));
        }
        return existing;
    }

    @Override
    public boolean remove(String key) {
        if (closed) throw new IllegalStateException("closed");
        Path path = dirPath.resolve(key);
        if (path.toFile().isFile())
            deleteFile(path);
        // todo check this is removed in watcher
        FileRecord fr = lastFileRecordMap.get(path.toFile());
        return fr != null;
    }

    @Override
    public void clear() {
        AtomicInteger count = new AtomicInteger();
        Stream<Path> files = getFiles();
        files.forEach((path) -> {
            try {
                deleteFile(path);
            } catch (Exception e) {
                count.incrementAndGet();
                // ignored at first.
            }
        });
        if (count.intValue() > 0) {
            pause(100);
            getFiles().forEach(this::deleteFile);
        }
    }

    @Override
    public boolean containsValue(final Bytes value) {
        throw new UnsupportedOperationException("todo");
    }


    private Stream<Path> getFiles() {
        try {
            return Files
                    .walk(dirPath)
                    .filter(p -> !Files.isDirectory(p))
                    .filter(this::isVisible);
        } catch (IOException e) {
            throw Jvm.rethrow(e);
        }
    }

    boolean isVisible(@NotNull Path p) {
        return !p.getFileName().startsWith(".");
    }

    @Nullable
    BytesStore getFileContents(@NotNull Path path, Bytes using) {
        File file = path.toFile();
        FileRecord<BytesStore> lastFileRecord = lastFileRecordMap.get(file);
        if (lastFileRecord != null && lastFileRecord.valid
                && file.lastModified() == lastFileRecord.timestamp) {
            return lastFileRecord.contents;
        }
        return getFileContentsFromDisk(path, using);
    }

    @Nullable
    Bytes getFileContentsFromDisk(@NotNull Path path, Bytes using) {
        for (int i = 1; i <= 5; i++) {
            try {
                return getFileContentsFromDisk0(path, using);
            } catch (IOException e) {
                pause(i * i * 2);
            }
        }
        return null;
    }

    private Bytes getFileContentsFromDisk0(@NotNull Path path, Bytes using) throws IOException {
        if (!Files.exists(path)) return null;
        File file = path.toFile();

        Buffers b = Buffers.BUFFERS.get();
        Bytes<ByteBuffer> readingBytes = b.valueBuffer;
        try (FileChannel fc = new FileInputStream(file).getChannel()) {
            readingBytes.ensureCapacity(fc.size());

            ByteBuffer dst = readingBytes.underlyingObject();
            dst.clear();

            fc.read(dst);

            readingBytes.readPosition(0);
            readingBytes.readLimit(dst.position());
            dst.flip();
        }
        return readingBytes;
    }

    private void writeToFile(@NotNull Path path, @NotNull BytesStore value) {
        BytesStore<?, ByteBuffer> writingBytes;
        if (value.underlyingObject() instanceof ByteBuffer) {
            writingBytes = value;
        } else {
            Buffers b = Buffers.BUFFERS.get();
            Bytes<ByteBuffer> valueBuffer = b.valueBuffer;
            valueBuffer.clear();
            valueBuffer.write(value);
            writingBytes = valueBuffer;
        }

        File file = path.toFile();
        File tmpFile = new File(file.getParentFile(), "." + file.getName() + "." + System.nanoTime());
        try (FileChannel fc = new FileOutputStream(tmpFile).getChannel()) {
            ByteBuffer byteBuffer = writingBytes.underlyingObject();
            byteBuffer.position(0);
            byteBuffer.limit((int) writingBytes.readLimit());
            fc.write(byteBuffer);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        for (int i = 1; i < 5; i++) {
            try {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                break;

            } catch (FileSystemException fse) {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Unable to rename file " + fse);
                try {
                    Thread.sleep(i * i * 2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void deleteFile(@NotNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void close() {
        closed = true;
        fileFpmWatcher.interrupt();
    }

    @Override
    public Asset asset() {
        return asset;
    }

    @Nullable
    @Override
    public KeyValueStore<String, Bytes, BytesStore> underlying() {
        return null;
    }

    @Override
    public void accept(final ReplicationEntry replicationEntry) {
        throw new UnsupportedOperationException("todo");
    }

    private class FPMWatcher implements Runnable {
        private final WatchService watcher;

        public FPMWatcher(WatchService watcher) {
            this.watcher = watcher;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    WatchKey key = null;
                    try {
                        key = processKey();
                    } catch (InterruptedException e) {
                        return;
                    } finally {
                        if (key != null) key.reset();
                    }
                }
            } catch (Throwable e) {
                if (!closed)
                    e.printStackTrace();
            }
        }

        @NotNull
        private WatchKey processKey() throws InterruptedException, IOException, InvalidSubscriberException {
            WatchKey key = watcher.take();
            for (WatchEvent<?> event : key.pollEvents()) {
                Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    // todo log a warning.
                    continue;
                }

                // get file fullName
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path fileName = ev.context();
                String mapKey = fileName.toString();
                if (mapKey.startsWith(".")) {
                    //this avoids temporary files being added to the map
                    continue;
                }
//                System.out.println("file: "+mapKey+" kind: "+kind);

                if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    Path p = dirPath.resolve(fileName);
                    BytesStore mapVal = getFileContentsFromDisk(p, null);

                    FileRecord<BytesStore> prev = lastFileRecordMap.get(p.toFile());
//                    if (mapVal == null) {
//                            System.out.println("Unable to read "+mapKey+", exists: "+p.toFile().exists());
//                    }
                    if (prev != null && BytesUtil.contentEqual(mapVal, prev.contents)) {
//                            System.out.println("... key: "+mapKey+" equal, last.keys: "+new TreeSet<>(lastFileRecordMap.keySet()));
                        continue;
                    }

                    if (mapVal == null) {
                        // todo this shouldn't happen.
                        if (prev != null)
                            mapVal = prev.contents;
                    } else {
//                            System.out.println("adding "+mapKey);
                        lastFileRecordMap.put(p.toFile(), new FileRecord<>(p.toFile().lastModified(), mapVal.copy()));
                    }
                    if (prev == null) {
                        subscriptions.notifyEvent(InsertedEvent.of(asset.fullName(), p.toFile().getName(), mapVal));
                    } else {
                        subscriptions.notifyEvent(UpdatedEvent.of(asset.fullName(), p.toFile().getName(), prev.contents, mapVal));
                        prev.contents.release();
                    }

                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    Path p = dirPath.resolve(fileName);

                    FileRecord<BytesStore> prev = lastFileRecordMap.remove(p.toFile());
                    BytesStore lastVal = prev == null ? null : prev.contents;
                    subscriptions.notifyEvent(RemovedEvent.of(asset.fullName(), p.toFile().getName(), lastVal));
                    if (prev != null)
                        prev.contents.release();
                }
            }
            return key;
        }
    }

    private static String bytesToString(Bytes bytes) {
        long pos = bytes.readPosition();
        try {
            return bytes.readUTFΔ();
        } finally {
            bytes.readPosition(pos);
        }
    }

    @Override
    public void applyChange(FilePerKeyValueStore store, ReplicationEntry remoteEntry) {
        if (remoteEntry.isDeleted()) {
            store.remove(bytesToString(remoteEntry.key()));
        } else {
            store.put(bytesToString(remoteEntry.key()), remoteEntry.value());
        }
    }

    @Override
    public Bytes getValue(FilePerKeyValueStore filePerKeyValueStore, Bytes key) {
        return get(bytesToString(key)).bytesForRead();
    }
}

