package com.example.monitored_service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.example.monitored.api.TemperatureApi;
import com.example.monitored.model.TemperatureReading;
import com.example.monitored.model.TemperatureReading.UnitEnum;

@RestController
public class TemperatureController implements TemperatureApi {

	private static final double SAMPLE_CELSIUS = 23.5;

	@Override
	public ResponseEntity<TemperatureReading> getCurrentTemperature(String unit, String location) {
		UnitEnum outputUnit;
		try {
			outputUnit = UnitEnum.valueOf(unit.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException | NullPointerException exception) {
			return ResponseEntity.badRequest().build();
		}

		double value = outputUnit == UnitEnum.CELSIUS
				? SAMPLE_CELSIUS
				: SAMPLE_CELSIUS * 9 / 5 + 32;

		TemperatureReading reading = new TemperatureReading(
				value,
				outputUnit,
				OffsetDateTime.now(ZoneOffset.UTC))
				.source("DEMO");
		if (location != null && !location.isBlank()) {
			reading.location(location);
		}
		return ResponseEntity.ok(reading);
	}
}
