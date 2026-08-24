package com.auctus.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private String token;
    /** Stable account id - the frontend keys per-agent data on it. */
    private String id;
    private String userId;
    private String email;
    private String role;
    private String firstName;
    private String lastName;
    private String status;
}
