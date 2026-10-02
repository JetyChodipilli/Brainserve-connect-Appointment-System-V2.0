package com.brainserve.appointment.visitor.domain;

import jakarta.persistence.Converter;

/** Keeps existing visitor mappings compatible with the shared encryption format. */
@Converter
public class SensitiveStringConverter
        extends com.brainserve.appointment.shared.application.SensitiveStringConverter {}
