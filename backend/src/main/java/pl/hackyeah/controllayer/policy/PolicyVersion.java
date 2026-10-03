package pl.hackyeah.controllayer.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/** Jedna wersja polityki (V5__policy_version.sql). Append-only: nowa zmiana = nowy wiersz. */
@Entity
@Table(name = "policy_version")
public class PolicyVersion implements Persistable<Long> {

    @Id
    private long version;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String document;

    @Column(nullable = false, length = 64)
    private String hash;

    @Column(nullable = false, length = 100)
    private String author;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(length = 500)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PolicyVersion() {
    }

    PolicyVersion(long version, String document, String hash, String author, String source, String comment,
            Instant createdAt) {
        this.version = version;
        this.document = document;
        this.hash = hash;
        this.author = author;
        this.source = source;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    ActivePolicy toActive() {
        return new ActivePolicy(version, hash, PolicyJson.fromJson(document), author, source, comment, createdAt);
    }

    @Override
    public Long getId() {
        return version;
    }

    /** Zawsze INSERT — kolizja numeru wersji kończy się błędem zamiast nadpisania historii. */
    @Override
    public boolean isNew() {
        return true;
    }

    public long getVersion() {
        return version;
    }

    public String getHash() {
        return hash;
    }

    public String getAuthor() {
        return author;
    }

    public String getSource() {
        return source;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
