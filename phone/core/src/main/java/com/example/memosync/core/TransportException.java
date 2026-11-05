package com.example.memosync.core;

/** Raised when a transport cannot deliver a payload (peer offline, send failed). */
public class TransportException extends RuntimeException {

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
