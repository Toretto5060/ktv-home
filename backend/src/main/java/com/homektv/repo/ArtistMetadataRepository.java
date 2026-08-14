package com.homektv.repo;

import com.homektv.domain.ArtistMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtistMetadataRepository extends JpaRepository<ArtistMetadata, String> {
}
