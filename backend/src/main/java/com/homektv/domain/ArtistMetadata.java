package com.homektv.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 歌手元数据实体，对应 artist_metadata 表。
 * 按歌手名去重，存储头像、性别、来源平台。
 */
@Entity
@Table(name = "artist_metadata")
public class ArtistMetadata {

    @Id
    @Column(name = "artist_name", nullable = false, length = 200)
    private String artistName;

    @Column(name = "avatar_url", length = 1000)
    private String avatarUrl;

    @Column(nullable = false, length = 20)
    private String gender = "未知";

    @Column(length = 20)
    private String source;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    // ---- getters / setters ----
    public String getArtistName() { return artistName; }
    public void setArtistName(String artistName) { this.artistName = artistName; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
