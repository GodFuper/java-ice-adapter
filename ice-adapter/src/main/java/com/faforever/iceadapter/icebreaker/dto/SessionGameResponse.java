package com.faforever.iceadapter.icebreaker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionGameResponse(String id, boolean forceRelay, List<IceServerDto> servers) {}
