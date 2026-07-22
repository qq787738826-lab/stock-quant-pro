package com.stockquant.server.agent.ingestion;

import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.RegisterSecurityIdentityCommand;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.RegisterSourceSecurityIdentityMappingCommand;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityIdentity;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SourceSecurityIdentityMapping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityIdentityService {

    private final SecurityIdentityRepository repository;
    private final SecurityEventCanonicalHasher hasher;

    public SecurityIdentityService(
            SecurityIdentityRepository repository,
            SecurityEventCanonicalHasher hasher
    ) {
        this.repository = repository;
        this.hasher = hasher;
    }

    @Transactional
    public SecurityIdentity registerIdentity(RegisterSecurityIdentityCommand command) {
        IngestionValidation.required(command, "command");
        String logicalKey = hasher.securityLogicalKey(
                command.recordNamespace(), command.identityAuthority(),
                command.identityStableId(), command.identityContractVersion());
        SecurityIdentity value = repository.insertIdentity(
                logicalKey, command.recordNamespace(), command.identityAuthority(),
                command.identityStableId(), command.identityContractVersion(),
                command.assuranceLevel()).orElseGet(() -> repository.findIdentity(logicalKey)
                .orElseThrow(() -> conflict("security identity conflict has no winner")));
        if (value.recordNamespace() != command.recordNamespace()
                || !value.identityAuthority().equals(command.identityAuthority())
                || !value.identityStableId().equals(command.identityStableId())
                || !value.identityContractVersion().equals(command.identityContractVersion())
                || value.assuranceLevel() != command.assuranceLevel()) {
            throw conflict("security identity logical key resolved to different content");
        }
        return value;
    }

    @Transactional
    public SourceSecurityIdentityMapping registerMapping(
            RegisterSourceSecurityIdentityMappingCommand command
    ) {
        IngestionValidation.required(command, "command");
        SecurityIdentity identity = repository.findIdentity(command.securityLogicalKey())
                .orElseThrow(() -> conflict("security identity does not exist"));
        if (identity.recordNamespace() != command.recordNamespace()) {
            throw conflict("identity mapping namespace does not match identity namespace");
        }
        if (command.mappingAssuranceLevel().conservativeWith(identity.assuranceLevel())
                != command.mappingAssuranceLevel()) {
            throw conflict("mapping assurance cannot exceed identity assurance");
        }
        String logicalKey = hasher.mappingLogicalKey(
                command.recordNamespace(), command.source(), command.sourceVersion(),
                command.sourceInstrumentId(), command.mappingContractVersion());
        SourceSecurityIdentityMapping value = repository.insertMapping(
                logicalKey, command.recordNamespace(), command.source(), command.sourceVersion(),
                command.sourceInstrumentId(), identity, command.mappingContractVersion(),
                command.mappingAssuranceLevel()).orElseGet(
                        () -> repository.findMappingByLogicalKey(logicalKey).orElseThrow(
                                () -> conflict("identity mapping conflict has no winner")));
        if (value.recordNamespace() != command.recordNamespace()
                || !value.source().equals(command.source())
                || !value.sourceVersion().equals(command.sourceVersion())
                || !value.sourceInstrumentId().equals(command.sourceInstrumentId())
                || !value.securityLogicalKey().equals(command.securityLogicalKey())
                || !value.mappingContractVersion().equals(command.mappingContractVersion())
                || value.mappingAssuranceLevel() != command.mappingAssuranceLevel()) {
            throw conflict("identity mapping logical key resolved to different content");
        }
        return value;
    }

    private static IngestionDataConflictException conflict(String message) {
        return new IngestionDataConflictException(message);
    }
}
