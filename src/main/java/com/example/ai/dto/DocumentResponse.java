package com.example.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DocumentResponse {
    private String fileName;
    private int chunksProcessed;
    private String message;
}
