package com.stockquant.server.agent.ingestion;

import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityIdentity;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SourceSecurityIdentityMapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class SecurityIdentityRepository {

    private static final String IDENTITY_COLUMNS = """
            id, security_logical_key, record_namespace, identity_authority,
            identity_stable_id, identity_contract_version, assurance_level, recorded_at
            """;
    private static final String MAPPING_COLUMNS = """
            id, mapping_logical_key, record_namespace, source, source_version,
            source_instrument_id, security_identity_id, security_logical_key,
            mapping_contract_version, mapping_assurance_level, recorded_at
            """;

    private final JdbcTemplate jdbc;

    public SecurityIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<SecurityIdentity> insertIdentity(
            String logicalKey,
            RunNamespace namespace,
            String authority,
            String stableId,
            String contractVersion,
            AssuranceLevel assurance
    ) {
        return jdbc.query("""
                INSERT INTO security_identity_registry(
                    security_logical_key, record_namespace, identity_authority,
                    identity_stable_id, identity_contract_version, assurance_level
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING
                """ + IDENTITY_COLUMNS, this::mapIdentity, logicalKey, namespace.name(),
                authority, stableId, contractVersion, assurance.name()).stream().findFirst();
    }

    public Optional<SecurityIdentity> findIdentity(String logicalKey) {
        return jdbc.query("SELECT " + IDENTITY_COLUMNS
                        + " FROM security_identity_registry WHERE security_logical_key=?",
                this::mapIdentity, logicalKey).stream().findFirst();
    }

    Optional<SourceSecurityIdentityMapping> insertMapping(
            String mappingLogicalKey,
            RunNamespace namespace,
            String source,
            String sourceVersion,
            String sourceInstrumentId,
            SecurityIdentity identity,
            String mappingContractVersion,
            AssuranceLevel assurance
    ) {
        return jdbc.query("""
                INSERT INTO source_security_identity_mappings(
                    mapping_logical_key, record_namespace, source, source_version,
                    source_instrument_id, security_identity_id, security_logical_key,
                    mapping_contract_version, mapping_assurance_level
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING
                """ + MAPPING_COLUMNS, this::mapMapping, mappingLogicalKey, namespace.name(),
                source, sourceVersion, sourceInstrumentId, identity.id(),
                identity.securityLogicalKey(), mappingContractVersion, assurance.name())
                .stream().findFirst();
    }

    public Optional<SourceSecurityIdentityMapping> findMappingByLogicalKey(String logicalKey) {
        return jdbc.query("SELECT " + MAPPING_COLUMNS
                        + " FROM source_security_identity_mappings WHERE mapping_logical_key=?",
                this::mapMapping, logicalKey).stream().findFirst();
    }

    public Optional<SourceSecurityIdentityMapping> findMapping(
            RunNamespace namespace,
            String source,
            String sourceVersion,
            String sourceInstrumentId
    ) {
        return jdbc.query("SELECT " + MAPPING_COLUMNS
                        + " FROM source_security_identity_mappings "
                        + "WHERE record_namespace=? AND source=? AND source_version=? "
                        + "AND source_instrument_id=? AND mapping_contract_version=?",
                this::mapMapping, namespace.name(), source, sourceVersion, sourceInstrumentId,
                SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION)
                .stream().findFirst();
    }

    private SecurityIdentity mapIdentity(ResultSet result, int row) throws SQLException {
        return new SecurityIdentity(
                result.getLong("id"), result.getString("security_logical_key"),
                RunNamespace.valueOf(result.getString("record_namespace")),
                result.getString("identity_authority"), result.getString("identity_stable_id"),
                result.getString("identity_contract_version"),
                AssuranceLevel.valueOf(result.getString("assurance_level")),
                instant(result.getObject("recorded_at")));
    }

    private SourceSecurityIdentityMapping mapMapping(ResultSet result, int row)
            throws SQLException {
        return new SourceSecurityIdentityMapping(
                result.getLong("id"), result.getString("mapping_logical_key"),
                RunNamespace.valueOf(result.getString("record_namespace")),
                result.getString("source"), result.getString("source_version"),
                result.getString("source_instrument_id"),
                result.getLong("security_identity_id"),
                result.getString("security_logical_key"),
                result.getString("mapping_contract_version"),
                AssuranceLevel.valueOf(result.getString("mapping_assurance_level")),
                instant(result.getObject("recorded_at")));
    }

    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return (Instant) value;
    }
}
