package com.example.monitored_service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.example.monitored.api.ConversionApi;
import com.example.monitored.model.ConversionResult;
import com.example.monitored.model.ConversionResult.InputUnitEnum;
import com.example.monitored.model.ConversionResult.OutputUnitEnum;

@RestController
public class ConversionController implements ConversionApi {

	@Override
	public ResponseEntity<ConversionResult> celsiusToFahrenheit(Double value) {
		return ResponseEntity.ok(createResult(
				value,
				InputUnitEnum.CELSIUS,
				value * 9 / 5 + 32,
				OutputUnitEnum.FAHRENHEIT,
				"F = C * 9/5 + 32"));
	}

	@Override
	public ResponseEntity<ConversionResult> fahrenheitToCelsius(Double value) {
		return ResponseEntity.ok(createResult(
				value,
				InputUnitEnum.FAHRENHEIT,
				(value - 32) * 5 / 9,
				OutputUnitEnum.CELSIUS,
				"C = (F - 32) * 5/9"));
	}

	private ConversionResult createResult(
			double input,
			InputUnitEnum inputUnit,
			double output,
			OutputUnitEnum outputUnit,
			String formula) {
		double roundedOutput = BigDecimal.valueOf(output)
				.setScale(2, RoundingMode.HALF_UP)
				.doubleValue();
		return new ConversionResult(
				input,
				inputUnit,
				roundedOutput,
				outputUnit,
				OffsetDateTime.now(ZoneOffset.UTC))
				.formula(formula);
	}
}
