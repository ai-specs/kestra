package io.kestra.webserver.controllers.api;

/**
 * dsh marker exception for HTTP-standard empty-body 404s (anti-probing: no problem document,
 * no detail). Thrown by the apps controllers on any not-found/invalid path; rendered by
 * {@code ErrorController#error(HttpRequest, NotFoundResponseException)} through the standard
 * Micronaut error pipeline.
 *
 * <p>It MUST be thrown (never converted to a raw {@code HttpResponse.status(NOT_FOUND)}
 * return inside the controller): an early raw 404 returned while a streaming request body is
 * still unconsumed triggers the Micronaut drain loop OOM (Kestra#17620; fixed upstream by
 * Kestra#17633 by routing error responses through the framework error processor).
 */
public class NotFoundResponseException extends RuntimeException {
}
