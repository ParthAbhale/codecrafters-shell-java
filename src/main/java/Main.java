import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Main {
    private static final List<String> BUILTINS = Arrays.asList("echo", "exit", "type", "pwd", "cd", "jobs");
    private static String currentDirectory = System.getProperty("user.dir");

    private static class Job {
        int jobNumber;
        long pid;
        String command;
        Process process; // may be null if we couldn't get a Process handle (rare)
        String status; // "Running" or "Done"

        Job(int jobNumber, long pid, String command, Process process) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.command = command;
            this.process = process;
            this.status = "Running";
        }
    }

    private static final List<Job> backgroundJobs = new ArrayList<>();

    // Job numbers are recycled: if the table is empty, the next job is [1];
    // otherwise it's one more than the highest job number currently held.
    private static int computeNextJobNumber() {
        if (backgroundJobs.isEmpty()) {
            return 1;
        }
        int highest = 0;
        for (Job job : backgroundJobs) {
            if (job.jobNumber > highest) {
                highest = job.jobNumber;
            }
        }
        return highest + 1;
    }

    private static class Redirection {
        String stdoutFile = null;
        boolean stdoutAppend = false;
        String stderrFile = null;
        boolean stderrAppend = false;
    }

    private static boolean isDoubleQuoteEscapable(char c) {
        return c == '"' || c == '\\' || c == '$' || c == '`';
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean hasToken = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') {
                    inSingleQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (inDoubleQuotes) {
                if (c == '"') {
                    inDoubleQuotes = false;
                } else if (c == '\\' && i + 1 < input.length() && isDoubleQuoteEscapable(input.charAt(i + 1))) {
                    current.append(input.charAt(i + 1));
                    i++;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\\' && i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    hasToken = true;
                    i++;
                } else if (c == '\'') {
                    inSingleQuotes = true;
                    hasToken = true;
                } else if (c == '"') {
                    inDoubleQuotes = true;
                    hasToken = true;
                } else if (Character.isWhitespace(c)) {
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                } else if (c == '>') {
                    // '>' is an operator character. If the token being built so far is
                    // exactly "1" or "2", fold it in as an fd prefix (1>, 2>, 2>>).
                    // Otherwise, flush whatever was being built as its own token first.
                    String pending = current.toString();
                    boolean fdPrefix = pending.equals("1") || pending.equals("2");
                    if (hasToken && !fdPrefix) {
                        tokens.add(pending);
                        current.setLength(0);
                    }
                    current.append('>');
                    if (i + 1 < input.length() && input.charAt(i + 1) == '>') {
                        current.append('>');
                        i++;
                    }
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                } else if (c == '&') {
                    // '&' is also a standalone operator token (background execution marker).
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                    tokens.add("&");
                } else if (c == '|') {
                    // '|' is the pipeline operator (standalone token, like '&').
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                    tokens.add("|");
                } else {
                    current.append(c);
                    hasToken = true;
                }
            }
        }

        if (hasToken) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static String markerFor(int index, int size) {
        if (index == size - 1) {
            return "+";
        } else if (index == size - 2) {
            return "-";
        } else {
            return " ";
        }
    }

    private static void printJobLine(Job job, String marker) {
        String statusField = String.format("%-24s", job.status);
        String commandDisplay = job.status.equals("Running") ? job.command + " &" : job.command;
        System.out.println("[" + job.jobNumber + "]" + marker + "  " + statusField + commandDisplay);
    }

    // Detects jobs that have exited since we last checked, prints a Done line for each
    // (using markers computed against the full current job list), then removes them from
    // the table. Safe to call before every prompt and inside the jobs builtin.
    private static void reapFinishedJobs() {
        for (Job job : backgroundJobs) {
            if (job.process != null && !job.process.isAlive() && !job.status.equals("Done")) {
                job.status = "Done";
            }
        }

        int n = backgroundJobs.size();
        for (int j = 0; j < n; j++) {
            Job job = backgroundJobs.get(j);
            if (job.status.equals("Done")) {
                printJobLine(job, markerFor(j, n));
            }
        }

        backgroundJobs.removeIf(job -> job.status.equals("Done"));
    }

    private static String findExecutableInPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }

        String[] directories = pathEnv.split(File.pathSeparator);
        for (String dir : directories) {
            if (dir.isEmpty()) {
                continue;
            }
            File candidate = new File(dir, command);
            if (candidate.isFile() && Files.isExecutable(candidate.toPath())) {
                return candidate.getPath();
            }
        }
        return null;
    }

    private static List<String> parseRedirection(List<String> tokens, Redirection redir) {
        List<String> filtered = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals(">") || t.equals("1>")) {
                if (i + 1 < tokens.size()) {
                    redir.stdoutFile = tokens.get(++i);
                    redir.stdoutAppend = false;
                }
            } else if (t.equals(">>") || t.equals("1>>")) {
                if (i + 1 < tokens.size()) {
                    redir.stdoutFile = tokens.get(++i);
                    redir.stdoutAppend = true;
                }
            } else if (t.equals("2>")) {
                if (i + 1 < tokens.size()) {
                    redir.stderrFile = tokens.get(++i);
                    redir.stderrAppend = false;
                }
            } else if (t.equals("2>>")) {
                if (i + 1 < tokens.size()) {
                    redir.stderrFile = tokens.get(++i);
                    redir.stderrAppend = true;
                }
            } else {
                filtered.add(t);
            }
        }
        return filtered;
    }

    // Splits a token list on top-level "|" tokens into separate command segments.
    private static List<List<String>> splitPipeline(List<String> tokens) {
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String t : tokens) {
            if (t.equals("|")) {
                segments.add(current);
                current = new ArrayList<>();
            } else {
                current.add(t);
            }
        }
        segments.add(current);
        return segments;
    }

    // Executes a builtin command using explicit stdin/stdout/stderr streams rather than the
    // global System.in/System.out/System.err. This lets the same logic run either as a normal
    // foreground command (wrapping the real System streams) or as one stage of a pipeline
    // (wrapping piped streams connected to neighboring stages), without the concurrency hazards
    // of swapping JVM-global System.out from multiple pipeline-stage threads at once.
    private static void executeBuiltin(String command, List<String> parts,
                                        java.io.InputStream stdin, java.io.PrintStream out, java.io.PrintStream err) {
        switch (command) {
            case "exit": {
                int code = 0;
                if (parts.size() > 1) {
                    try {
                        code = Integer.parseInt(parts.get(1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                out.flush();
                System.exit(code);
                break;
            }
            case "echo": {
                String output = String.join(" ", parts.subList(1, parts.size()));
                out.println(output);
                break;
            }
            case "pwd": {
                out.println(currentDirectory);
                break;
            }
            case "cd": {
                if (parts.size() < 2) {
                    break;
                }
                String target = parts.get(1);
                if (target.equals("~")) {
                    target = System.getenv("HOME");
                } else if (target.startsWith("~/")) {
                    target = System.getenv("HOME") + target.substring(1);
                }
                File targetDir = new File(target);
                if (!targetDir.isAbsolute()) {
                    targetDir = new File(currentDirectory, target);
                }
                if (targetDir.isDirectory()) {
                    try {
                        currentDirectory = targetDir.getCanonicalPath();
                    } catch (Exception e) {
                        err.println("cd: " + target + ": No such file or directory");
                    }
                } else {
                    err.println("cd: " + target + ": No such file or directory");
                }
                break;
            }
            case "jobs": {
                // Detect jobs that have exited (non-blocking check) and mark them Done.
                for (Job job : backgroundJobs) {
                    if (job.process != null && !job.process.isAlive()) {
                        job.status = "Done";
                    }
                }

                int n = backgroundJobs.size();
                for (int j = 0; j < n; j++) {
                    Job job = backgroundJobs.get(j);
                    String statusField = String.format("%-24s", job.status);
                    String commandDisplay = job.status.equals("Running") ? job.command + " &" : job.command;
                    out.println("[" + job.jobNumber + "]" + markerFor(j, n) + "  " + statusField + commandDisplay);
                }

                // Reap: remove Done jobs now that they've been reported once.
                backgroundJobs.removeIf(job -> job.status.equals("Done"));
                break;
            }
            case "type": {
                if (parts.size() < 2) {
                    break;
                }
                String target = parts.get(1);
                if (BUILTINS.contains(target)) {
                    out.println(target + " is a shell builtin");
                } else {
                    String foundPath = findExecutableInPath(target);
                    if (foundPath != null) {
                        out.println(target + " is " + foundPath);
                    } else {
                        out.println(target + ": not found");
                    }
                }
                break;
            }
            default: {
                err.println(command + ": not a builtin");
                break;
            }
        }
    }

    private static void runExternalProgram(String command, List<String> parts, Redirection redir, boolean background) {
        try {
            ProcessBuilder builder = new ProcessBuilder(parts);
            builder.directory(new File(currentDirectory));
            if (redir.stdoutFile != null) {
                File out = new File(redir.stdoutFile);
                if (!out.isAbsolute()) out = new File(currentDirectory, redir.stdoutFile);
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                if (redir.stdoutAppend) {
                    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(out));
                } else {
                    builder.redirectOutput(ProcessBuilder.Redirect.to(out));
                }
            } else {
                builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            if (redir.stderrFile != null) {
                File err = new File(redir.stderrFile);
                if (!err.isAbsolute()) err = new File(currentDirectory, redir.stderrFile);
                if (err.getParentFile() != null) err.getParentFile().mkdirs();
                if (redir.stderrAppend) {
                    builder.redirectError(ProcessBuilder.Redirect.appendTo(err));
                } else {
                    builder.redirectError(ProcessBuilder.Redirect.to(err));
                }
            } else {
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            Process process = builder.start();

            if (background) {
                int jobNumber = computeNextJobNumber();
                Job job = new Job(jobNumber, process.pid(), String.join(" ", parts), process);
                backgroundJobs.add(job);
                System.out.println("[" + jobNumber + "] " + process.pid());
                // Do not wait — return immediately so the prompt comes back right away.
            } else {
                process.waitFor();
            }
        } catch (Exception e) {
            System.out.println(command + ": " + e.getMessage());
        }
    }

    // A single stage of a pipeline: either an external command (cmd != null, builtin info unused)
    // or a builtin (isBuiltin = true), tagged with its own stderr redirection.
    private static class Stage {
        List<String> command;
        boolean isBuiltin;
        Redirection redir;

        Stage(List<String> command, boolean isBuiltin, Redirection redir) {
            this.command = command;
            this.isBuiltin = isBuiltin;
            this.redir = redir;
        }
    }

    // Copies all bytes from `in` to `out` on the calling thread, then closes `out` (so the
    // downstream reader sees EOF) and closes `in`. Used to pump data between a builtin's
    // stream and a neighboring stage's stream, since builtins don't have OS-level file
    // descriptors that the OS pipe machinery (ProcessBuilder.startPipeline) can wire directly.
    private static void pump(java.io.InputStream in, java.io.OutputStream out) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
            // Reader/writer side closed (e.g. downstream exited early, broken pipe equivalent).
        } finally {
            try { out.close(); } catch (Exception ignored) {}
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    // Runs a pipeline that may freely mix external commands and shell builtins at any
    // position. External-to-external boundaries still go through native OS pipes via
    // ProcessBuilder (so long-running streaming producers like "tail -f" behave exactly as
    // they would directly under startPipeline); any boundary touching a builtin is bridged
    // with an in-JVM pipe (PipedInputStream/PipedOutputStream) plus a pump thread, since a
    // builtin has no OS file descriptor of its own to hand to the kernel's pipe machinery.
    private static void runPipeline(List<List<String>> segments, List<Redirection> segmentRedirs,
                                     Redirection redir, boolean background) {
        int n = segments.size();
        List<Stage> stages = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<String> seg = segments.get(i);
            if (seg.isEmpty()) {
                System.out.println("syntax error: empty command in pipeline");
                return;
            }
            String cmd = seg.get(0);
            boolean builtin = BUILTINS.contains(cmd);
            if (!builtin) {
                String executablePath = findExecutableInPath(cmd);
                if (executablePath == null) {
                    System.out.println(cmd + ": not found");
                    return;
                }
            }
            stages.add(new Stage(seg, builtin, segmentRedirs.get(i)));
        }

        // Fast path: an all-external pipeline keeps using native OS pipes end-to-end via
        // ProcessBuilder.startPipeline, preserving prompt streaming behavior for cases like
        // "tail -f file | head -n 5" without going through any Java-side byte pumping at all.
        boolean anyBuiltin = false;
        for (Stage s : stages) {
            if (s.isBuiltin) { anyBuiltin = true; break; }
        }
        if (!anyBuiltin) {
            runAllExternalPipeline(segments, segmentRedirs, redir, background);
            return;
        }

        // Mixed pipeline: build per-stage stdin sources and stdout destinations.
        // streamIn[i]  = the InputStream stage i should read from
        // streamOut[i] = the OutputStream stage i should write to
        java.io.InputStream[] streamIn = new java.io.InputStream[n];
        java.io.OutputStream[] streamOut = new java.io.OutputStream[n];
        List<Thread> pumpThreads = new ArrayList<>();
        List<Process> processes = new ArrayList<>();
        List<Thread> builtinThreads = new ArrayList<>();

        try {
            // First stage reads from the shell's real stdin.
            streamIn[0] = System.in;
            // Last stage writes to the shell's real stdout, unless redirected to a file.
            if (redir.stdoutFile != null) {
                File out = new File(redir.stdoutFile);
                if (!out.isAbsolute()) out = new File(currentDirectory, redir.stdoutFile);
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                streamOut[n - 1] = new java.io.FileOutputStream(out, redir.stdoutAppend);
            } else {
                streamOut[n - 1] = System.out;
            }

            // Wire up the n-1 internal boundaries: stage i's stdout feeds stage i+1's stdin.
            for (int i = 0; i < n - 1; i++) {
                java.io.PipedOutputStream pipeOut = new java.io.PipedOutputStream();
                java.io.PipedInputStream pipeIn = new java.io.PipedInputStream(pipeOut, 8192);
                streamOut[i] = pipeOut;
                streamIn[i + 1] = pipeIn;
            }

            // Start every external-process stage now (so they all run concurrently with
            // builtins and with each other), wiring Redirect.PIPE on whichever side(s)
            // touch a builtin boundary (we'll pump bytes for those by hand) and using the
            // resolved stream directly via a pump thread for boundaries that touch the
            // shell's own stdin/stdout/file (also handled by a pump thread for uniformity,
            // except true process-to-process boundaries which use native OS pipes).
            for (int i = 0; i < n; i++) {
                Stage stage = stages.get(i);
                if (stage.isBuiltin) {
                    continue;
                }

                ProcessBuilder pb = new ProcessBuilder(stage.command);
                pb.directory(new File(currentDirectory));

                // stderr: per-stage redirection if present, else inherit.
                if (stage.redir.stderrFile != null) {
                    File errF = new File(stage.redir.stderrFile);
                    if (!errF.isAbsolute()) errF = new File(currentDirectory, stage.redir.stderrFile);
                    if (errF.getParentFile() != null) errF.getParentFile().mkdirs();
                    pb.redirectError(stage.redir.stderrAppend
                            ? ProcessBuilder.Redirect.appendTo(errF)
                            : ProcessBuilder.Redirect.to(errF));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }
                // Last stage's stderr can additionally be overridden by trailing redirection
                // on the whole command line (handled identically to the all-external path).
                if (i == n - 1 && redir.stderrFile != null) {
                    File errF = new File(redir.stderrFile);
                    if (!errF.isAbsolute()) errF = new File(currentDirectory, redir.stderrFile);
                    if (errF.getParentFile() != null) errF.getParentFile().mkdirs();
                    pb.redirectError(redir.stderrAppend
                            ? ProcessBuilder.Redirect.appendTo(errF)
                            : ProcessBuilder.Redirect.to(errF));
                }

                // stdin side
                if (i == 0) {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                }

                // stdout side
                if (i == n - 1 && redir.stdoutFile != null) {
                    File out = new File(redir.stdoutFile);
                    if (!out.isAbsolute()) out = new File(currentDirectory, redir.stdoutFile);
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    pb.redirectOutput(redir.stdoutAppend
                            ? ProcessBuilder.Redirect.appendTo(out)
                            : ProcessBuilder.Redirect.to(out));
                } else if (i == n - 1) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }

                Process proc = pb.start();
                processes.add(proc);

                // Feed this process's stdin from streamIn[i], if its stdin wasn't INHERIT.
                if (i != 0) {
                    java.io.InputStream src = streamIn[i];
                    java.io.OutputStream dst = proc.getOutputStream();
                    Thread t = new Thread(() -> pump(src, dst));
                    t.start();
                    pumpThreads.add(t);
                }
                // Drain this process's stdout into streamOut[i], if its stdout wasn't INHERIT/file.
                if (i != n - 1) {
                    java.io.InputStream src = proc.getInputStream();
                    java.io.OutputStream dst = streamOut[i];
                    Thread t = new Thread(() -> pump(src, dst));
                    t.start();
                    pumpThreads.add(t);
                }
            }

            // Start every builtin stage on its own thread, using the resolved streams.
            for (int i = 0; i < n; i++) {
                Stage stage = stages.get(i);
                if (!stage.isBuiltin) {
                    continue;
                }
                final int idx = i;
                java.io.InputStream in = streamIn[idx];
                java.io.OutputStream rawOut = streamOut[idx];
                java.io.PrintStream out = (rawOut instanceof java.io.PrintStream)
                        ? (java.io.PrintStream) rawOut
                        : new java.io.PrintStream(rawOut, true);
                java.io.PrintStream errStream;
                if (stage.redir.stderrFile != null) {
                    File errF = new File(stage.redir.stderrFile);
                    if (!errF.isAbsolute()) errF = new File(currentDirectory, stage.redir.stderrFile);
                    if (errF.getParentFile() != null) errF.getParentFile().mkdirs();
                    errStream = new java.io.PrintStream(new java.io.FileOutputStream(errF, stage.redir.stderrAppend));
                } else {
                    errStream = System.err;
                }
                final java.io.PrintStream finalErr = errStream;
                Thread t = new Thread(() -> {
                    try {
                        executeBuiltin(stage.command.get(0), stage.command, in, out, finalErr);
                    } finally {
                        // Close this stage's output so the downstream reader sees EOF,
                        // unless it's the shell's own stdout (never close that).
                        if (out != System.out) {
                            out.close();
                        }
                        if (idx != 0 && in != System.in) {
                            try { in.close(); } catch (Exception ignored) {}
                        }
                        if (finalErr != System.err) {
                            finalErr.close();
                        }
                    }
                });
                builtinThreads.add(t);
                t.start();
            }

            if (background) {
                // Background mixed pipelines: report immediately using the last stage's PID
                // if it's an external process, otherwise there's no PID to report (a builtin
                // tail stage has no OS process); we approximate by not printing a PID line
                // when the last stage is a builtin, since none exists.
                int jobNumber = computeNextJobNumber();
                StringBuilder cmdDisplay = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    if (i > 0) cmdDisplay.append(" | ");
                    cmdDisplay.append(String.join(" ", segments.get(i)));
                }
                if (!processes.isEmpty()) {
                    Process lastProc = processes.get(processes.size() - 1);
                    Job job = new Job(jobNumber, lastProc.pid(), cmdDisplay.toString(), lastProc);
                    backgroundJobs.add(job);
                    System.out.println("[" + jobNumber + "] " + lastProc.pid());
                }
                // Do not wait — return immediately so the prompt comes back right away.
                return;
            }

            // Foreground: wait for the last stage to finish (process or builtin thread),
            // mirroring real shell behavior where the prompt returns once the pipeline's
            // final command completes, even if upstream producers are still draining.
            Stage lastStage = stages.get(n - 1);
            if (lastStage.isBuiltin) {
                builtinThreads.get(builtinThreads.size() - 1).join();
            } else {
                processes.get(processes.size() - 1).waitFor();
            }
        } catch (Exception e) {
            System.out.println("pipeline: " + e.getMessage());
        }
    }

    // Runs a pipeline of two or more external commands, connecting stdout of each
    // stage to stdin of the next. Per-stage stderr redirection (e.g. "cat file 2> err.txt | wc")
    // is honored on that stage's own process; stdout redirection on a non-last stage is
    // meaningless in a pipeline (its stdout must stay wired to the next stage's stdin) and
    // is ignored, matching shell semantics. The very last stage's stdout/stderr fall back to
    // the trailing redirection on the full command line, if any, else inherit the shell's.
    // We rely on ProcessBuilder.startPipeline, which wires the pipes natively (no Java-side
    // copying loop), so data streams through promptly — important for `tail -f | head -n 5`.
    private static void runAllExternalPipeline(List<List<String>> segments, List<Redirection> segmentRedirs,
                                     Redirection redir, boolean background) {
        List<ProcessBuilder> builders = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        for (List<String> segment : segments) {
            if (segment.isEmpty()) {
                System.out.println("syntax error: empty command in pipeline");
                return;
            }
            String cmd = segment.get(0);
            String executablePath = findExecutableInPath(cmd);
            if (executablePath == null) {
                System.out.println(cmd + ": not found");
                unresolved.add(cmd);
                continue;
            }
            ProcessBuilder pb = new ProcessBuilder(segment);
            pb.directory(new File(currentDirectory));
            builders.add(pb);
        }

        if (!unresolved.isEmpty()) {
            return;
        }

        // First stage inherits stdin from the shell.
        builders.get(0).redirectInput(ProcessBuilder.Redirect.INHERIT);

        // Apply each stage's own stderr redirection (independent of the stdout->stdin
        // piping), defaulting to INHERIT when the stage didn't specify one.
        for (int i = 0; i < builders.size(); i++) {
            ProcessBuilder pb = builders.get(i);
            Redirection segRedir = segmentRedirs.get(i);
            if (segRedir.stderrFile != null) {
                File err = new File(segRedir.stderrFile);
                if (!err.isAbsolute()) err = new File(currentDirectory, segRedir.stderrFile);
                if (err.getParentFile() != null) err.getParentFile().mkdirs();
                pb.redirectError(segRedir.stderrAppend
                        ? ProcessBuilder.Redirect.appendTo(err)
                        : ProcessBuilder.Redirect.to(err));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
        }

        // Last stage: stdout redirection (trailing on the full command line) if present,
        // else inherit. A stderr redirection trailing the full command line overrides
        // whatever the last segment itself specified, matching how a trailing "2> x" after
        // the final command in a real shell pipeline applies to that final command.
        ProcessBuilder last = builders.get(builders.size() - 1);
        if (redir.stdoutFile != null) {
            File out = new File(redir.stdoutFile);
            if (!out.isAbsolute()) out = new File(currentDirectory, redir.stdoutFile);
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            if (redir.stdoutAppend) {
                last.redirectOutput(ProcessBuilder.Redirect.appendTo(out));
            } else {
                last.redirectOutput(ProcessBuilder.Redirect.to(out));
            }
        } else {
            last.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }

        if (redir.stderrFile != null) {
            File err = new File(redir.stderrFile);
            if (!err.isAbsolute()) err = new File(currentDirectory, redir.stderrFile);
            if (err.getParentFile() != null) err.getParentFile().mkdirs();
            if (redir.stderrAppend) {
                last.redirectError(ProcessBuilder.Redirect.appendTo(err));
            } else {
                last.redirectError(ProcessBuilder.Redirect.to(err));
            }
        }

        try {
            List<Process> processes = ProcessBuilder.startPipeline(builders);

            if (background) {
                Process lastProcess = processes.get(processes.size() - 1);
                int jobNumber = computeNextJobNumber();
                StringBuilder cmdDisplay = new StringBuilder();
                for (int i = 0; i < segments.size(); i++) {
                    if (i > 0) cmdDisplay.append(" | ");
                    cmdDisplay.append(String.join(" ", segments.get(i)));
                }
                Job job = new Job(jobNumber, lastProcess.pid(), cmdDisplay.toString(), lastProcess);
                backgroundJobs.add(job);
                System.out.println("[" + jobNumber + "] " + lastProcess.pid());
            } else {
                // Only wait on the final stage, mirroring how a shell returns its prompt as
                // soon as the foreground pipeline's last command finishes. Earlier stages
                // (e.g. a "tail -f" that never exits on its own) are left running in the
                // background; they'll naturally terminate via SIGPIPE on their next write
                // once the downstream reader is gone, without blocking the prompt here.
                Process lastProcess = processes.get(processes.size() - 1);
                lastProcess.waitFor();
            }
        } catch (Exception e) {
            System.out.println("pipeline: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            reapFinishedJobs();
            System.out.print("$ ");
            System.out.flush();

            String input = reader.readLine();
            if (input == null) {
                break;
            }
            if (input.isBlank()) {
                continue;
            }

            List<String> tokens = tokenize(input);
            if (tokens.isEmpty()) {
                continue;
            }

            boolean background = false;
            if (tokens.get(tokens.size() - 1).equals("&")) {
                background = true;
                tokens.remove(tokens.size() - 1);
                if (tokens.isEmpty()) {
                    continue;
                }
            }

            // Check for a pipeline (top-level "|" token) before doing single-command
            // redirection parsing, since each pipeline segment needs independent handling.
            boolean hasPipe = tokens.contains("|");

            if (hasPipe) {
                List<List<String>> rawSegments = splitPipeline(tokens);
                Redirection redir = new Redirection();
                List<List<String>> cleanSegments = new ArrayList<>();
                List<Redirection> segmentRedirs = new ArrayList<>();
                for (int i = 0; i < rawSegments.size(); i++) {
                    List<String> seg = rawSegments.get(i);
                    Redirection segRedir = new Redirection();
                    seg = parseRedirection(seg, segRedir);
                    cleanSegments.add(seg);
                    segmentRedirs.add(segRedir);
                    if (i == rawSegments.size() - 1) {
                        // The trailing redirection on the full command line (e.g. "... | wc > out")
                        // takes effect on the last stage's stdout/stderr, overriding/augmenting
                        // whatever that stage parsed for itself.
                        redir.stdoutFile = segRedir.stdoutFile;
                        redir.stdoutAppend = segRedir.stdoutAppend;
                        redir.stderrFile = segRedir.stderrFile;
                        redir.stderrAppend = segRedir.stderrAppend;
                    }
                }
                runPipeline(cleanSegments, segmentRedirs, redir, background);
                continue;
            }

            Redirection redir = new Redirection();
            List<String> parts = parseRedirection(tokens, redir);
            if (parts.isEmpty()) {
                continue;
            }

            String command = parts.get(0);
            java.io.PrintStream stdout = System.out;
            java.io.PrintStream stderr = System.err;

            try {
                if (redir.stdoutFile != null) {
                    File out = new File(redir.stdoutFile);
                    if (!out.isAbsolute()) out = new File(currentDirectory, redir.stdoutFile);
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(out, redir.stdoutAppend)));
                }
                if (redir.stderrFile != null) {
                    File err = new File(redir.stderrFile);
                    if (!err.isAbsolute()) err = new File(currentDirectory, redir.stderrFile);
                    if (err.getParentFile() != null) err.getParentFile().mkdirs();
                    System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(err, redir.stderrAppend)));
                }

                if (BUILTINS.contains(command)) {
                    executeBuiltin(command, parts, System.in, System.out, System.err);
                } else {
                    String executablePath = findExecutableInPath(command);
                    if (executablePath != null) {
                        runExternalProgram(command, parts, redir, background);
                    } else {
                        System.out.println(command + ": not found");
                    }
                }
            } finally {
                if (System.out != stdout) {
                    System.out.close();
                    System.setOut(stdout);
                }
                if (System.err != stderr) {
                    System.err.close();
                    System.setErr(stderr);
                }
            }
        }
    }
}