package com.seathold.engine;

/**
 * Typed result object. Instead of exceptions for expected failures,
 * every engine method returns a Result so callers can branch cleanly.
 */
public final class Result<T> {

    private final T value;
    private final String error;

    private Result(T value, String error) {
        this.value = value;
        this.error = error;
    }

    public static <T> Result<T> ok(T value) {
        return new Result<>(value, null);
    }

    public static <T> Result<T> err(String reason) {
        return new Result<>(null, reason);
    }

    public boolean isOk()  { return error == null; }
    public boolean isErr() { return error != null; }

    public T getValue()    { return value; }
    public String getError(){ return error; }

    @Override
    public String toString() {
        return isOk() ? "Ok(" + value + ")" : "Err(" + error + ")";
    }
}
