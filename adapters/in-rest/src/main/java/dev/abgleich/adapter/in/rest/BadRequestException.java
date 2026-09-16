package dev.abgleich.adapter.in.rest;

final class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    BadRequestException(String message) {
        super(message);
    }
}
