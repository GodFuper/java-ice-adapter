package com.faforever.iceadapter.icebreaker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IceServerDto(String id, String username, String credential, List<String> urls) {}
