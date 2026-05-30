package com.gdep;

public class GracefulException extends RuntimeException {
    private boolean printHelp = false;

    public GracefulException(String message) {
        super(message);
    }

    public GracefulException(String message, Throwable cause) {
        super(message, cause);
    }

    public GracefulException(Throwable cause) {
        super(cause);
    }

    public GracefulException withHelp() {
        this.printHelp = true;
        return this;
    }

    public boolean shouldPrintHelp() {
        return this.printHelp;
    }
}
