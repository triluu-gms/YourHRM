package vn.yourhrm.people.internal.application.assignment;

/** A non-blocking finding returned with a change (e.g. outside the pay band, manager not derivable). */
public record Warning(String code, String message) {
}
