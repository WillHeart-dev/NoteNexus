package com.notenexus.rag.document.service;

import com.notenexus.rag.auth.CurrentUserService;
import com.notenexus.rag.document.mapper.DocumentMapper;
import com.notenexus.rag.document.mapper.DocumentUploadChunkMapper;
import com.notenexus.rag.document.mapper.DocumentUploadSessionMapper;
import com.notenexus.rag.document.model.dto.UploadDocumentRequest;
import com.notenexus.rag.document.model.entity.DocumentEntity;
import com.notenexus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.notenexus.rag.engine.storage.ObjectStorageService;
import com.notenexus.rag.group.service.GroupMembershipService;
import com.notenexus.rag.ingestion.vector.VectorIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("文档上传服务")
class DocumentUploadServiceTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentUploadSessionMapper documentUploadSessionMapper;

    @Mock
    private DocumentUploadChunkMapper documentUploadChunkMapper;

    @Mock
    private GroupMembershipService groupMembershipService;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private VectorIngestionService vectorIngestionService;

    @Mock
    private ElasticsearchChunkIndexService elasticsearchChunkIndexService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    @DisplayName("直接上传文档时应计算并保存文件 SHA-256")
    void shouldCalculateFileHashForDirectUpload() {
        byte[] content = "hello notenexus rag".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "note.md",
                "text/markdown",
                content
        );
        UploadDocumentRequest request = new UploadDocumentRequest();
        request.setGroupId(1L);
        request.setFile(file);
        when(objectStorageService.getDefaultBucket()).thenReturn("notenexus-documents");
        when(groupMembershipService.requireGroupOwner(1L))
                .thenReturn(new CurrentUserService.CurrentUser(1L, "admin", "ADMIN"));
        when(documentMapper.insert(any(DocumentEntity.class))).thenAnswer(invocation -> {
            DocumentEntity document = invocation.getArgument(0);
            document.setId(99L);
            return 1;
        });

        DocumentUploadService service = new DocumentUploadService(
                documentMapper,
                documentUploadSessionMapper,
                documentUploadChunkMapper,
                groupMembershipService,
                objectStorageService,
                vectorIngestionService,
                elasticsearchChunkIndexService,
                applicationEventPublisher
        );

        Long documentId = service.uploadDocument(1L, request);

        assertThat(documentId).isEqualTo(99L);
        ArgumentCaptor<DocumentEntity> documentCaptor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentMapper).insert(documentCaptor.capture());
        assertThat(documentCaptor.getValue().getFileHash())
                .isEqualTo("31b9498ab69cdd12c68dc4261bf8d2327b49cb7a1f99ecc6b61eb394c4e29ca0");
    }
}
