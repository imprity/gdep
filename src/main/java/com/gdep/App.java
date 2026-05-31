package com.gdep;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

public class App {
    public static void main(String[] args) {
        final String cwd = System.getProperty("user.dir");
        final String cacheDir = Path.of(cwd, "cache").toString();

        List<Command> commands = List.of(new Commands.Dirs(), new Commands.Files(), new Commands.Pack());

        // if no arguments were given, just print help and exit
        if (args.length == 0) {
            printHelp(System.err, commands);
            System.exit(1);
        }

        // if the fist argument is help, just print help and exit
        if (args.length >= 1 && args[0].equals("help")) {
            printHelp(System.out, commands);
            System.exit(0);
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
            System.exit(1);
        }

        try {
            ExternalCodeManager externalCodeManager = new ExternalCodeManager(cwd, cacheDir);
            List<SourceCode> sourceCodes = externalCodeManager.getExternalSourceCodes();

            toRun.run(sourceCodes, Arrays.copyOfRange(args, 1, args.length));
        } catch (GracefulException e) {
            System.err.println(e.getMessage());
            if (e.shouldPrintHelp()) {
                System.err.println("");
                printHelp(System.err, commands);
            }
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printHelp(PrintStream out, List<Command> commands) {
        out.println("gdep");
        out.println("");
        out.println("usage:");
        out.println("");
        out.println("help : prints this meesage");
        for (final Command c : commands) {
            out.println(c.getName() + " : " + c.getDescription());
        }
    }
}
