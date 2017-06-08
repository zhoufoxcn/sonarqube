/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.db.DbSession;
import org.sonar.db.MyBatis;

import static java.lang.String.format;
import static java.lang.Thread.currentThread;

public class DBSessionsImpl implements DBSessions {
  private static final Logger LOG = Loggers.get(DBSessionsImpl.class);

  private static final ThreadLocal<Boolean> CACHING_ENABLED = ThreadLocal.withInitial(() -> Boolean.FALSE);
  private final ThreadLocal<NonClosingDbSessionSupplier> regularDbSession = ThreadLocal.withInitial(this::buildRegularDbSessionSupplier);
  private final ThreadLocal<NonClosingDbSessionSupplier> batchDbSession = ThreadLocal.withInitial(this::buildBatchDbSessionSupplier);

  private final MyBatis myBatis;

  public DBSessionsImpl(MyBatis myBatis) {
    this.myBatis = myBatis;
  }

  private NonClosingDbSessionSupplier buildRegularDbSessionSupplier() {
    LOG.info("{} called buildRegularDbSessionSupplier", currentThread());
    return new NonClosingDbSessionSupplier(() -> {
      NonClosingDbSession res = new NonClosingDbSession(myBatis.openSession(false));
      LOG.info("{} created regular DbSession {}", currentThread(), res);
      return res;
    });
  }

  private NonClosingDbSessionSupplier buildBatchDbSessionSupplier() {
    LOG.info("{} called buildBatchDbSessionSupplier", currentThread());
    return new NonClosingDbSessionSupplier(() -> {
      NonClosingDbSession res = new NonClosingDbSession(myBatis.openSession(true));
      LOG.info("{} created batch DbSession {}", currentThread(), res);
      return res;
    });
  }

  @Override
  public void enableCaching() {
    LOG.info("{} enabled caching", currentThread());
    CACHING_ENABLED.set(Boolean.TRUE);
  }

  @Override
  public DbSession openSession(boolean batch) {
    LOG.info("{} called openSession({}) (caching={})", currentThread(), batch, CACHING_ENABLED.get());
    if (!CACHING_ENABLED.get()) {
      DbSession res = myBatis.openSession(batch);
      LOG.info("{} created non cached {} session (batch={})", currentThread(), res, batch);
      return res;
    }
    if (batch) {
      return batchDbSession.get().get();
    }
    return regularDbSession.get().get();
  }

  @Override
  public void disableCaching() {
    LOG.info("{} disabled caching", currentThread());
    close(regularDbSession, "regular");
    close(batchDbSession, "batch");
    regularDbSession.remove();
    batchDbSession.remove();
    CACHING_ENABLED.remove();
  }

  public void close(ThreadLocal<NonClosingDbSessionSupplier> dbSessionThreadLocal, String label) {
    NonClosingDbSessionSupplier nonClosingDbSessionSupplier = dbSessionThreadLocal.get();
    boolean getCalled = nonClosingDbSessionSupplier.isPopulated();
    LOG.info("{} attempts closing on {} session (getCalled={})", currentThread(), label, getCalled);
    if (getCalled) {
      try {
        NonClosingDbSession res = nonClosingDbSessionSupplier.get();
        LOG.info("{} closes {}", currentThread(), res);
        res.getDelegate().close();
      } catch (Exception e) {
        LOG.error(format("Failed to close %s connection in %s", label, currentThread()), e);
      }
    }
  }

  /**
   * A {@link Supplier} of {@link NonClosingDbSession} which logs whether {@link Supplier#get() get} has been called at
   * least once, delegates the actual supplying to the a specific {@link Supplier<NonClosingDbSession>} instance and
   * caches the supplied {@link NonClosingDbSession}.
   */
  private static final class NonClosingDbSessionSupplier implements Supplier<NonClosingDbSession> {
    private final Supplier<NonClosingDbSession> delegate;
    private NonClosingDbSession dbSession;

    NonClosingDbSessionSupplier(Supplier<NonClosingDbSession> delegate) {
      this.delegate = delegate;
    }

    @Override
    public NonClosingDbSession get() {
      if (dbSession == null) {
        dbSession = Objects.requireNonNull(delegate.get());
      }
      return dbSession;
    }

    boolean isPopulated() {
      return dbSession != null;
    }
  }

  /**
   * A wrapper of a {@link DbSession} instance which does not call the wrapped {@link DbSession}'s
   * {@link DbSession#close() close} method.
   */
  private static final class NonClosingDbSession implements DbSession {
    private final DbSession delegate;

    private NonClosingDbSession(DbSession delegate) {
      this.delegate = delegate;
    }

    public DbSession getDelegate() {
      return delegate;
    }

    ///////////////////////
    // overridden with change of behavior
    ///////////////////////
    @Override
    public void close() {
      // rollback in case session is dirty so that no statement leaks from one use of the DbSession to another
      // super.close() would do such rollback before actually closing, we need to keep this behavior
      delegate.rollback();
    }

    ///////////////////////
    // overridden with NO change of behavior
    ///////////////////////
    @Override
    public <T> T selectOne(String statement) {
      return delegate.selectOne(statement);
    }

    @Override
    public <T> T selectOne(String statement, Object parameter) {
      return delegate.selectOne(statement, parameter);
    }

    @Override
    public <E> List<E> selectList(String statement) {
      return delegate.selectList(statement);
    }

    @Override
    public <E> List<E> selectList(String statement, Object parameter) {
      return delegate.selectList(statement, parameter);
    }

    @Override
    public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
      return delegate.selectList(statement, parameter, rowBounds);
    }

    @Override
    public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
      return delegate.selectMap(statement, mapKey);
    }

    @Override
    public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
      return delegate.selectMap(statement, parameter, mapKey);
    }

    @Override
    public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey, RowBounds rowBounds) {
      return delegate.selectMap(statement, parameter, mapKey, rowBounds);
    }

    @Override
    public void select(String statement, Object parameter, ResultHandler handler) {
      delegate.select(statement, parameter, handler);
    }

    @Override
    public void select(String statement, ResultHandler handler) {
      delegate.select(statement, handler);
    }

    @Override
    public void select(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {
      delegate.select(statement, parameter, rowBounds, handler);
    }

    @Override
    public int insert(String statement) {
      return delegate.insert(statement);
    }

    @Override
    public int insert(String statement, Object parameter) {
      return delegate.insert(statement, parameter);
    }

    @Override
    public int update(String statement) {
      return delegate.update(statement);
    }

    @Override
    public int update(String statement, Object parameter) {
      return delegate.update(statement, parameter);
    }

    @Override
    public int delete(String statement) {
      return delegate.delete(statement);
    }

    @Override
    public int delete(String statement, Object parameter) {
      return delegate.delete(statement, parameter);
    }

    @Override
    public void commit() {
      delegate.commit();
    }

    @Override
    public void commit(boolean force) {
      delegate.commit(force);
    }

    @Override
    public void rollback() {
      delegate.rollback();
    }

    @Override
    public void rollback(boolean force) {
      delegate.rollback(force);
    }

    @Override
    public List<BatchResult> flushStatements() {
      return delegate.flushStatements();
    }

    @Override
    public void clearCache() {
      delegate.clearCache();
    }

    @Override
    public Configuration getConfiguration() {
      return delegate.getConfiguration();
    }

    @Override
    public <T> T getMapper(Class<T> type) {
      return delegate.getMapper(type);
    }

    @Override
    public Connection getConnection() {
      return delegate.getConnection();
    }
  }
}
