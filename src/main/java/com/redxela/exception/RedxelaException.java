package com.redxela.exception;

/** Error de regla de negocio del simulador (se traduce a HTTP 400). */
public class RedxelaException extends RuntimeException {
    public RedxelaException(String message) {
        super(message);
    }
}
