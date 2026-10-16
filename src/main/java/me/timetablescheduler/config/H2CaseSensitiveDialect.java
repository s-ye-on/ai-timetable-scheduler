package me.timetablescheduler.config;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.env.spi.IdentifierCaseStrategy;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelper;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelperBuilder;

/** Preserves existing H2 names when DATABASE_TO_UPPER=false disables case folding. */
public class H2CaseSensitiveDialect extends H2Dialect {

	public H2CaseSensitiveDialect(DialectResolutionInfo info) {
		super(info);
	}

	@Override
	public IdentifierHelper buildIdentifierHelper(IdentifierHelperBuilder builder, DatabaseMetaData metadata)
		throws SQLException {
		if (metadata != null
			&& !metadata.storesUpperCaseIdentifiers()
			&& !metadata.storesLowerCaseIdentifiers()
			&& !metadata.storesMixedCaseIdentifiers()) {
			// H2 keeps the original spelling here; Hibernate otherwise defaults to UPPER.
			builder.setUnquotedCaseStrategy(IdentifierCaseStrategy.MIXED);
		}
		return super.buildIdentifierHelper(builder, metadata);
	}
}
