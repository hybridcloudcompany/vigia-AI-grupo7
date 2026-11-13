package com.example.monitored_service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class HttpMetricsRecorder extends OncePerRequestFilter {

	private static final Duration MAX_WINDOW = Duration.ofHours(1);
	private final Deque<RequestSample> samples = new ArrayDeque<>();

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		long startedAt = System.nanoTime();
		try {
			filterChain.doFilter(request, response);
		} catch (ServletException | IOException | RuntimeException exception) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			throw exception;
		} finally {
			record(response.getStatus(), System.nanoTime() - startedAt);
		}
	}

	private synchronized void record(int status, long durationNanos) {
		Instant now = Instant.now();
		removeExpired(now.minus(MAX_WINDOW));
		samples.addLast(new RequestSample(now, status, durationNanos));
	}

	public synchronized List<RequestSample> samplesSince(Instant since) {
		removeExpired(Instant.now().minus(MAX_WINDOW));
		List<RequestSample> result = new ArrayList<>();
		for (RequestSample sample : samples) {
			if (!sample.timestamp().isBefore(since)) {
				result.add(sample);
			}
		}
		return result;
	}

	private void removeExpired(Instant cutoff) {
		while (!samples.isEmpty() && samples.peekFirst().timestamp().isBefore(cutoff)) {
			samples.removeFirst();
		}
	}

	public record RequestSample(Instant timestamp, int status, long durationNanos) {
	}
}
