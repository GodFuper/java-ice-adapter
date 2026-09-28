package com.faforever.iceadapter.icebreaker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionTokenResponse(String jwt) {}
