package com.gdep;

import java.io.PrintStream;
import java.util.*;

public class App {
    public static void main(String[] args) {
        int exitCode = run(args);
        System.exit(exitCode);
    }

    private static int run(String[] args) {
        final String cwd = System.getProperty("user.dir");
        final String cacheDir = System.getProperty("gdep.internal.cache.dir");

        List<Command> commands = List.of(new Commands.Dirs(), new Commands.Files(), new Commands.Pack());

        if (cacheDir == null) {
            System.err.println("gdep.internal.cache.dir is not set.");
            System.err.println("if you are using raw gdep.jar, pass gdep.internal.cache.dir with this command");
            System.err.println("java -Dgdep.internal.cache.dir=/cache/dir/you/want");
            System.err.println("");
            printHelp(System.err, commands);
            return 1;
        }

        // if no arguments were given, just print help and exit
        if (args.length == 0) {
            printHelp(System.err, commands);
            return 1;
        }

        // if the fist argument is help, just print help and exit
        if (args.length >= 1 && args[0].equals("help")) {
            printHelp(System.out, commands);
            return 0;
        }

        Command toRun = null;

        for (Command command : commands) {
            if (command.getName().equals(args[0])) {
                toRun = command;
            }
        }

        if (toRun == null) {
            System.err.println("Unknown command: " + args[0]);
            System.err.println("");
            printHelp(System.err, commands);
            return 1;
        }

        try {
            CodeManager codeManager = new CodeManager(cwd, cacheDir);
            List<SourceCode> sourceCodes = codeManager.getExternalSourceCodes();

            toRun.run(sourceCodes, Arrays.copyOfRange(args, 1, args.length));
        } catch (GracefulException e) {
            System.err.println(e.getMessage());
            if (e.shouldPrintHelp()) {
                System.err.println("");
                printHelp(System.err, commands);
            }
            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }

        return 0;
    }

    private static void printHelp(PrintStream out, List<Command> commands) {
        out.println("gdep");
        out.println("");
        out.println("usage:");
        out.println("");
        out.println("help : prints this message");
        for (final Command c : commands) {
            out.println(c.getName() + " : " + c.getDescription());
        }
    }
}
