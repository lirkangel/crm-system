package com.crm.foundation.Audit;

import com.crm.foundation.Domain.Attachment;
import com.crm.foundation.Domain.RefreshToken;
import com.crm.foundation.Domain.User;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

import java.time.Instant;
import java.time.LocalDateTime;

public class AuditListener {
    @PrePersist
      private void beforeCreate(Object object) {
          if (object instanceof User user) {
              Instant now = Instant.now();
              if (user.getCreatedAt() == null) {
                  user.setCreatedAt(now);
              }
              user.setUpdatedAt(now);
              if (user.getEnabled() == null) {
                  user.setEnabled(true);
              }
              if (user.getFailedLogin() == null) {
                  user.setFailedLogin(0);
              }
              if (user.getVersion() == null) {
                  user.setVersion(0L);
              }
          }

          if (object instanceof RefreshToken token && token.getCreatedAt() == null) {
              token.setCreatedAt(Instant.now());
          }

          if (object instanceof Attachment attachment && attachment.getCreatedAt() == null) {
              attachment.setCreatedAt(Instant.now());
          }
      }

      @PreUpdate
      private void beforeUpdate(Object object) {
          if (object instanceof User user) {
              user.setUpdatedAt(Instant.now());
          }
      }

      @PreRemove
      private void beforeDelete(Object object) {
          // Usually empty unless you need very light validation.
      }
}
