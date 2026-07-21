package com.scaling.exercise.config;

/**
 * NOTE: Cache status tracking was moved directly into ProductController.
 *
 * The original interceptor approach (using afterCompletion) didn't work
 * because Spring writes the response body BEFORE afterCompletion runs.
 * With chunked transfer encoding, headers can't be set after the body
 * starts streaming.
 *
 * The fix: each controller method calls prepareCacheTracking() before
 * the service method, then setCacheHeaders() after it returns but
 * before returning the result. This ensures headers are set before
 * the response body is written.
 *
 * This file is kept as documentation of the problem. In a real app,
 * you could use a OncePerRequestFilter with a response wrapper to
 * buffer the response and add headers, but that adds complexity.
 */
// No longer used — cache tracking is in ProductController.setCacheHeaders()
