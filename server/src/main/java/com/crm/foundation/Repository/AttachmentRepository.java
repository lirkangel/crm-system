package com.crm.foundation.Repository;

import com.crm.foundation.Domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByPluginId(String pluginId);

    List<Attachment> findByOwner_Id(UUID ownerId);
}
