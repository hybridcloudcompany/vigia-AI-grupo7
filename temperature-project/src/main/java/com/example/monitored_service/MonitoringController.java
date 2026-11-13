package com.example.monitored_service;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

@RestController
public class MonitoringController {

	private static final Map<String, Duration> WINDOWS = Map.of(
			"1m", Duration.ofMinutes(1),
			"5m", Duration.ofMinutes(5),
			"15m", Duration.ofMinutes(15),
			"1h", Duration.ofHours(1));

	private final ObjectProvider<PrometheusMeterRegistry> prometheusRegistry;
	private final HttpMetricsRecorder metricsRecorder;

	public MonitoringController(
			ObjectProvider<PrometheusMeterRegistry> prometheusRegistry,
			HttpMetricsRecorder metricsRecorder) {
		this.prometheusRegistry = prometheusRegistry;
		this.metricsRecorder = metricsRecorder;
	}

	@GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> getMetrics() {
		PrometheusMeterRegistry registry = prometheusRegistry.getIfAvailable();
		if (registry == null) {
			return ResponseEntity.status(503).body("Prometheus metrics registry is not available");
		}
		return ResponseEntity.ok(registry.scrape());
	}

	@GetMapping("/monitoring/summary")
	public ResponseEntity<Map<String, Object>> getMonitoringSummary(
			@RequestParam(defaultValue = "5m") String window) {
		Duration duration = WINDOWS.get(window);
		if (duration == null) {
			return ResponseEntity.badRequest().build();
		}

		List<HttpMetricsRecorder.RequestSample> samples = metricsRecorder.samplesSince(
				OffsetDateTime.now(ZoneOffset.UTC).minus(duration).toInstant());
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
		summary.put("memory", memoryStats());
		summary.put("garbageCollector", garbageCollectorStats());
		summary.put("latency", latencyStats(window, samples, duration));
		summary.put("errors", errorStats(window, samples));
		summary.put("threads", threadStats());
		return ResponseEntity.ok(summary);
	}

	private Map<String, Object> memoryStats() {
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("heapUsedBytes", heap.getUsed());
		stats.put("heapMaxBytes", heap.getMax());
		if (heap.getMax() > 0) {
			stats.put("heapUsagePercent", heap.getUsed() * 100.0 / heap.getMax());
		}
		stats.put("nonHeapUsedBytes", nonHeap.getUsed());
		return stats;
	}

	private Map<String, Object> garbageCollectorStats() {
		long collections = 0;
		long pauseMillis = 0;
		List<String> names = new ArrayList<>();
		for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
			names.add(collector.getName());
			if (collector.getCollectionCount() >= 0) {
				collections += collector.getCollectionCount();
			}
			if (collector.getCollectionTime() >= 0) {
				pauseMillis += collector.getCollectionTime();
			}
		}
		return Map.of(
				"collectorName", String.join(", ", names),
				"collectionsTotal", collections,
				"pauseTimeTotalSeconds", pauseMillis / 1000.0);
	}

	private Map<String, Object> latencyStats(
			String window,
			List<HttpMetricsRecorder.RequestSample> samples,
			Duration duration) {
		List<Long> sorted = samples.stream()
				.map(HttpMetricsRecorder.RequestSample::durationNanos)
				.sorted()
				.toList();
		return Map.of(
				"window", window,
				"requestsPerMinute", samples.size() * 60.0 / duration.toSeconds(),
				"p50Seconds", percentileSeconds(sorted, 0.50),
				"p95Seconds", percentileSeconds(sorted, 0.95),
				"p99Seconds", percentileSeconds(sorted, 0.99));
	}

	private double percentileSeconds(List<Long> sorted, double percentile) {
		if (sorted.isEmpty()) {
			return 0.0;
		}
		int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
		return sorted.get(index) / 1_000_000_000.0;
	}

	private Map<String, Object> errorStats(
			String window,
			List<HttpMetricsRecorder.RequestSample> samples) {
		Map<String, Long> byStatus = new TreeMap<>();
		long errors4xx = 0;
		long errors5xx = 0;
		for (HttpMetricsRecorder.RequestSample sample : samples) {
			byStatus.merge(Integer.toString(sample.status()), 1L, Long::sum);
			if (sample.status() >= 400 && sample.status() < 500) {
				errors4xx++;
			} else if (sample.status() >= 500) {
				errors5xx++;
			}
		}
		long total = samples.size();
		return Map.of(
				"window", window,
				"totalRequests", total,
				"errors4xx", errors4xx,
				"errors5xx", errors5xx,
				"errorRate4xxPercent", ratePercent(errors4xx, total),
				"errorRate5xxPercent", ratePercent(errors5xx, total),
				"byStatus", byStatus);
	}

	private double ratePercent(long errors, long total) {
		return total == 0 ? 0.0 : errors * 100.0 / total;
	}

	private Map<String, Object> threadStats() {
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		int blocked = 0;
		for (long threadId : threads.getAllThreadIds()) {
			ThreadInfo info = threads.getThreadInfo(threadId);
			if (info != null && info.getThreadState() == Thread.State.BLOCKED) {
				blocked++;
			}
		}
		return Map.of(
				"live", threads.getThreadCount(),
				"daemon", threads.getDaemonThreadCount(),
				"peak", threads.getPeakThreadCount(),
				"blocked", blocked);
	}
}
