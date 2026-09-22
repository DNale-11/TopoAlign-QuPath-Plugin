package io.github.dnale11.topoalign;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** One process per registration; arguments never pass through a shell. */
final class PythonRunner {
    private Process process;
    private volatile boolean cancelled;

    static List<String> command(String python, Path script, String sourceRoot, Path request) {
        var args = new ArrayList<>(List.of(python, "-u", script.toString(), "--request", request.toString()));
        if (!sourceRoot.isBlank()) args.addAll(List.of("--source-root", sourceRoot));
        return args;
    }

    void checkCancelled() {
        if (cancelled) throw new CancellationException("Registration cancelled");
    }

    void run(String python, Path script, String sourceRoot, Path request, Path log) throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command(python, script, sourceRoot, request));
        builder.directory(request.getParent().toFile());
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("MPLBACKEND", "Agg");
        synchronized (this) {
            checkCancelled();
            process = builder.start();
        }
        var shutdown = new Thread(this::cancel, "topoalign-process-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            int exit = process.waitFor();
            checkCancelled();
            if (exit != 0) throw new IOException("Python exited with code " + exit + ". See run.log.\n" + tail(log));
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* JVM is shutting down. */ }
            synchronized (this) {
                if (process.isAlive()) stopProcess();
                process = null;
            }
        }
    }

    synchronized void cancel() {
        cancelled = true;
        if (process != null) stopProcess();
    }

    private void stopProcess() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    static String tail(Path path) throws IOException {
        if (!Files.exists(path)) return "";
        try (var channel = FileChannel.open(path)) {
            channel.position(Math.max(0, channel.size() - 32000));
            var buffer = ByteBuffer.allocate(32000);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { /* bounded tail */ }
            buffer.flip();
            return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }
}
