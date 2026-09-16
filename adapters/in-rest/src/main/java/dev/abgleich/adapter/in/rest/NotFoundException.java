package dev.abgleich.adapter.in.rest;

final class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NotFoundException(String message) {
        super(message);
    }
}
