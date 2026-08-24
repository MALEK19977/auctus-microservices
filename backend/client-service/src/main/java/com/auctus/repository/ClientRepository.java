package com.auctus.repository;

import com.auctus.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, String> {

    Optional<Client> findByRib(String rib);

    Optional<Client> findByAccountNumber(String accountNumber);

    List<Client> findByCin(String cin);

    @Query("SELECT c FROM Client c WHERE LOWER(c.fullName) LIKE LOWER(CONCAT('%', :term, '%')) "
            + "ORDER BY c.fullName")
    List<Client> searchByName(@Param("term") String term);

    /**
     * One box that accepts whatever the agent has to hand: a RIB, an account
     * number, a CIN or a name.
     */
    @Query("SELECT c FROM Client c WHERE c.rib = :term OR c.accountNumber = :term "
            + "OR c.cin = :term OR LOWER(c.fullName) LIKE LOWER(CONCAT('%', :term, '%')) "
            + "ORDER BY c.fullName")
    List<Client> search(@Param("term") String term);
}
