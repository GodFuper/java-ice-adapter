package com.faforever.iceadapter.icebreaker;

import lombok.Getter;

@Getter
public class IcebreakerApiException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public IcebreakerApiException(int statusCode, String responseBody) {
        super("Icebreaker API error: HTTP " + statusCode + ", body: " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }
}
