package com.redxela.exception;

/** El almacén no tiene ninguna ubicación libre para guardar mercadería. */
public class WarehouseFullException extends RedxelaException {
    public WarehouseFullException(String message) {
        super(message);
    }
}
