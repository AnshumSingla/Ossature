package com.ossature.interviewer.db;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A normal JPA transaction manager that, right after each transaction begins, tells Postgres who is
 * acting so the row-level-security policies can apply (see {@link DbContext} and V7).
 *
 * <p>The settings are written with {@code set_config(..., is_local => true)}: they last exactly as long
 * as the transaction and are discarded at commit or rollback, so a pooled connection can never carry
 * one user's identity into the next transaction. A transaction that starts with no context sets nothing,
 * and the policies then match no rows - failing closed.
 */
public class RlsJpaTransactionManager extends JpaTransactionManager {

    private static final String SET_CONTEXT =
            "SELECT set_config('app.user_id', ?, true), set_config('app.is_owner', ?, true), set_config('app.scope', ?, true)";

    public RlsJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        DbContext.Ctx ctx = DbContext.current();
        if (ctx == null) {
            return;
        }
        DataSource dataSource = getDataSource();
        ConnectionHolder holder = dataSource == null
                ? null
                : (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
        if (holder == null) {
            // Never silently run a user's transaction without their identity applied.
            throw new CannotCreateTransactionException("Could not obtain the transaction's JDBC connection to apply the database context");
        }
        Connection connection = holder.getConnection();
        try (PreparedStatement ps = connection.prepareStatement(SET_CONTEXT)) {
            ps.setString(1, ctx.userId() == null ? "" : ctx.userId().toString());
            ps.setString(2, ctx.owner() ? "true" : "false");
            ps.setString(3, ctx.scope() == null ? "" : ctx.scope());
            ps.execute();
        } catch (SQLException e) {
            throw new CannotCreateTransactionException("Could not apply the database context", e);
        }
    }
}
