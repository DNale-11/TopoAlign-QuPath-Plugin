package io.github.dnale11.topoalign;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PythonRunnerTest {
    @TempDir Path temp;

    @Test void pathsRemainSeparateArguments() {
        var command = PythonRunner.command("C:/Python with spaces/python.exe", Path.of("桥 接.py"),
                "C:/project & data", Path.of("request file.json"));
        assertEquals(List.of("C:/Python with spaces/python.exe", "-u", "桥 接.py", "--request", "request file.json",
                "--source-root", "C:/project & data"), command);
    }

    @Test void cancellationBeforeLaunchPreventsProcessCreation() {
        var runner = new PythonRunner(); runner.cancel();
        assertThrows(CancellationException.class, () -> runner.run("nonexistent-executable", temp.resolve("bridge.py"),
                "", temp.resolve("request.json"), temp.resolve("log")));
    }

    @Test void logTailIsBoundedAndContainsLatestOutput() throws Exception {
        Path log = temp.resolve("run.log");
        Files.writeString(log, "x".repeat(100000) + "\nlatest result");
        String tail = PythonRunner.tail(log);
        assertTrue(tail.endsWith("latest result")); assertEquals(32000, tail.length());
    }

    @Test void reportsPythonFailureWithLog() throws Exception {
        Path script = temp.resolve("failure.py");
        Files.writeString(script, "print('deliberate failure', flush=True)\nraise SystemExit(3)\n");
        var runner = new PythonRunner();
        var error = assertThrows(java.io.IOException.class, () -> runner.run("python", script, "",
                temp.resolve("request.json"), temp.resolve("run.log")));
        assertTrue(error.getMessage().contains("code 3"));
        assertTrue(error.getMessage().contains("deliberate failure"));
    }

    @Test void cancelsRunningPythonProcess() throws Exception {
        Path script = temp.resolve("wait.py"), log = temp.resolve("run.log");
        Files.writeString(script, "import time\nprint('ready', flush=True)\ntime.sleep(60)\n");
        var runner = new PythonRunner();
        var future = CompletableFuture.runAsync(() -> {
            assertThrows(CancellationException.class, () -> runner.run("python", script, "", temp.resolve("request.json"), log));
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!PythonRunner.tail(log).contains("ready") && System.nanoTime() < deadline && !future.isDone())
                Thread.sleep(50);
            assertTrue(PythonRunner.tail(log).contains("ready"), "Python did not start");
            runner.cancel();
            future.get(10, TimeUnit.SECONDS);
        } finally { runner.cancel(); }
    }
}
