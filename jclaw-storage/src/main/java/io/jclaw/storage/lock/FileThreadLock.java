package io.jclaw.storage.lock;

import io.jclaw.contracts.turn.ThreadLock;
import io.jclaw.contracts.turn.TurnScope;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ThreadLock} over OS advisory file locks, one lock file per canonical thread.
 *
 * <p>An OS lock rather than a row in {@code runs.jsonl} because the store cannot do this job.
 * Checking "is a run active on this thread" and then recording a new run are two appends with a
 * window between them, and two processes racing through that window both pass the check, which is
 * exactly the race the lock exists to close. {@link FileChannel#tryLock()} is atomic at the kernel,
 * and it has a second property no TTL scheme matches: the lock vanishes the instant the holding
 * process dies, so a crashed worker never leaves a thread locked and nothing has to guess whether
 * a silent holder is slow or gone.
 *
 * <p>The scope of the guarantee is one host. That is the topology the JSONL stores support anyway;
 * a shared state directory over NFS is not a configuration jclaw claims to handle.
 *
 * <p>Within one JVM the runtime raises {@link OverlappingFileLockException} instead of contending,
 * which is reported as "held" rather than propagated: same answer, same caller.
 */
public final class FileThreadLock implements ThreadLock {

    private final Path directory;

    /** @param directory where lock files live; created on first use */
    public FileThreadLock(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath();
    }

    @Override
    public Optional<Held> tryAcquire(TurnScope scope) {
        Objects.requireNonNull(scope, "scope");
        Path file = lockFileFor(scope);
        FileChannel channel;
        try {
            Files.createDirectories(directory);
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open thread lock file " + file, e);
        }

        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldInThisProcess) {
            closeQuietly(channel);
            return Optional.empty();
        } catch (IOException e) {
            closeQuietly(channel);
            throw new UncheckedIOException("cannot lock " + file, e);
        }
        if (lock == null) {
            // Held by another process.
            closeQuietly(channel);
            return Optional.empty();
        }
        return Optional.of(new HeldFile(scope, channel, lock));
    }

    /**
     * The lock file for a scope.
     *
     * <p>Named by a hash of the full canonical key rather than the thread id: a project name is a
     * directory name and may contain anything, and two scopes must never map to one file.
     */
    Path lockFileFor(TurnScope scope) {
        String key = String.join(" ",
                scope.tenant(), scope.agent(), scope.project(), scope.thread().value());
        return directory.resolve(sha256(key) + ".lock");
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing useful to do: the lock was never taken.
        }
    }

    private static final class HeldFile implements Held {

        private final TurnScope scope;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean released;

        private HeldFile(TurnScope scope, FileChannel channel, FileLock lock) {
            this.scope = scope;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public TurnScope scope() {
            return scope;
        }

        @Override
        public synchronized void close() {
            if (released) {
                return;
            }
            released = true;
            try {
                lock.release();
            } catch (IOException ignored) {
                // Closing the channel below releases it regardless.
            } finally {
                closeQuietly(channel);
            }
        }
    }
}
