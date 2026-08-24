package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A bank client. Identity here is permanent: the RIB, the account number and the
 * enrolled signature specimen belong to the client for life, and every cheque they
 * issue must present exactly these values.
 */
@Entity
@Table(name = "clients", indexes = {
        @Index(name = "idx_client_rib", columnList = "rib", unique = true),
        @Index(name = "idx_client_account", columnList = "accountNumber", unique = true),
        @Index(name = "idx_client_cin", columnList = "cin"),
        @Index(name = "idx_client_full_name", columnList = "fullName")
})
@Data
public class Client {

    @Id
    private String clientId;

    private String clientType;

    private String firstName;
    private String lastName;
    private String fullName;

    private String cin;
    private LocalDate birthDate;
    private Integer age;
    private Boolean minor;
    private String guardianName;
    private String guardianCin;

    private String city;
    private String address;
    private String phone;
    private String email;

    @Column(unique = true, nullable = false)
    private String rib;

    @Column(unique = true, nullable = false)
    private String accountNumber;

    private String accountType;
    private String agencyCode;
    private String agencyName;
    private String agencyAddress;

    @Column(precision = 15, scale = 3)
    private BigDecimal balance;

    /** Folder + file of the enrolled signature specimen, used for manual comparison. */
    private String signatureDossier;
    private String signatureImage;

    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
