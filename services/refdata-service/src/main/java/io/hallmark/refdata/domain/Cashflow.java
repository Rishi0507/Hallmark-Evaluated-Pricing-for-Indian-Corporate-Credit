package io.hallmark.refdata.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Cashflow(LocalDate paymentDate, LocalDate dueDate, double amountPerUnit, boolean principal, String event) {}
