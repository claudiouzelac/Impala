// Copyright 2014 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.analysis;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.AnalyticWindow.Boundary;
import com.cloudera.impala.analysis.AnalyticWindow.BoundaryType;
import com.cloudera.impala.catalog.AggregateFunction;
import com.cloudera.impala.catalog.Function;
import com.cloudera.impala.catalog.Type;
import com.cloudera.impala.catalog.ScalarType;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.common.TreeNode;
import com.cloudera.impala.service.FeSupport;
import com.cloudera.impala.thrift.TColumnValue;
import com.cloudera.impala.thrift.TExprNode;
import com.cloudera.impala.util.TColumnValueUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Representation of an analytic function call with OVER clause.
 * All "subexpressions" (such as the actual function call parameters as well as the
 * partition/ordering exprs, etc.) are embedded as children in order to allow expr
 * substitution:
 *   function call params: child 0 .. #params
 *   partition exprs: children #params + 1 .. #params + #partition-exprs
 *   ordering exprs:
 *     children #params + #partition-exprs + 1 ..
 *       #params + #partition-exprs + #order-by-elements
 *   exprs in windowing clause: remaining children
 *
 * Note that it's wrong to embed the FunctionCallExpr itself as a child,
 * because in 'COUNT(..) OVER (..)' the 'COUNT(..)' is not part of a standard aggregate
 * computation and must not be substituted as such. However, the parameters of the
 * analytic function call might reference the output of an aggregate computation
 * and need to be substituted as such; example: COUNT(COUNT(..)) OVER (..)
 */
public class AnalyticExpr extends Expr {
  private final static Logger LOG = LoggerFactory.getLogger(AnalyticExpr.class);

  private FunctionCallExpr fnCall_;
  private final List<Expr> partitionExprs_;
  // These elements are modified to point to the corresponding child exprs to keep them
  // in sync through expr substitutions.
  private List<OrderByElement> orderByElements_ = Lists.newArrayList();
  private AnalyticWindow window_;

  // If set, requires the window to be set to null in resetAnalysisState(). Required for
  // proper substitution/cloning because standardization may set a window that is illegal
  // in SQL, and hence, will fail analysis().
  private boolean resetWindow_ = false;

  // SQL string of this AnalyticExpr before standardization. Returned in toSqlImpl().
  private String sqlString_;

  private static String LEAD = "lead";
  private static String LAG = "lag";
  private static String FIRSTVALUE = "first_value";
  private static String LASTVALUE = "last_value";
  private static String RANK = "rank";
  private static String DENSERANK = "dense_rank";
  private static String ROWNUMBER = "row_number";
  private static String MIN = "min";
  private static String MAX = "max";
  private static String PERCENT_RANK = "percent_rank";
  private static String CUME_DIST = "cume_dist";
  private static String NTILE = "ntile";

  // Internal function used to implement FIRST_VALUE with a window rewrite and
  // additional null handling in the backend.
  public static String FIRST_VALUE_REWRITE = "first_value_rewrite";

  public AnalyticExpr(FunctionCallExpr fnCall, List<Expr> partitionExprs,
      List<OrderByElement> orderByElements, AnalyticWindow window) {
    Preconditions.checkNotNull(fnCall);
    fnCall_ = fnCall;
    partitionExprs_ = partitionExprs != null ? partitionExprs : new ArrayList<Expr>();
    if (orderByElements != null) orderByElements_.addAll(orderByElements);
    window_ = window;
    setChildren();
  }

  /**
   * clone() c'tor
   */
  protected AnalyticExpr(AnalyticExpr other) {
    super(other);
    fnCall_ = (FunctionCallExpr) other.fnCall_.clone();
    for (OrderByElement e: other.orderByElements_) {
      orderByElements_.add(e.clone());
    }
    partitionExprs_ = Expr.cloneList(other.partitionExprs_);
    window_ = (other.window_ != null ? other.window_.clone() : null);
    resetWindow_ = other.resetWindow_;
    sqlString_ = other.sqlString_;
    setChildren();
  }

  public FunctionCallExpr getFnCall() { return fnCall_; }
  public List<Expr> getPartitionExprs() { return partitionExprs_; }
  public List<OrderByElement> getOrderByElements() { return orderByElements_; }
  public AnalyticWindow getWindow() { return window_; }

  @Override
  public boolean equals(Object obj) {
    if (!super.equals(obj)) return false;
    AnalyticExpr o = (AnalyticExpr)obj;
    if (!fnCall_.equals(o.getFnCall())) return false;
    if ((window_ == null) != (o.window_ == null)) return false;
    if (window_ != null) {
      if (!window_.equals(o.window_)) return false;
    }
    return orderByElements_.equals(o.orderByElements_);
  }

  /**
   * Analytic exprs cannot be constant.
   */
  @Override
  public boolean isConstant() { return false; }

  @Override
  public Expr clone() { return new AnalyticExpr(this); }

  @Override
  public String toSqlImpl() {
    if (sqlString_ != null) return sqlString_;
    StringBuilder sb = new StringBuilder();
    sb.append(fnCall_.toSql()).append(" OVER (");
    boolean needsSpace = false;
    if (!partitionExprs_.isEmpty()) {
      sb.append("PARTITION BY ").append(Expr.toSql(partitionExprs_));
      needsSpace = true;
    }
    if (!orderByElements_.isEmpty()) {
      List<String> orderByStrings = Lists.newArrayList();
      for (OrderByElement e: orderByElements_) {
        orderByStrings.add(e.toSql());
      }
      if (needsSpace) sb.append(" ");
      sb.append("ORDER BY ").append(Joiner.on(", ").join(orderByStrings));
      needsSpace = true;
    }
    if (window_ != null) {
      if (needsSpace) sb.append(" ");
      sb.append(window_.toSql());
    }
    sb.append(")");
    return sb.toString();
  }

  @Override
  public String debugString() {
    return Objects.toStringHelper(this)
        .add("fn", getFnCall())
        .add("window", window_)
        .addValue(super.debugString())
        .toString();
  }

  @Override
  protected void toThrift(TExprNode msg) {
  }

  private static boolean isAnalyticFn(Function fn) {
    return fn instanceof AggregateFunction
        && ((AggregateFunction) fn).isAnalyticFn();
  }

  private static boolean isAnalyticFn(Function fn, String fnName) {
    return isAnalyticFn(fn) && fn.functionName().equals(fnName);
  }

  public static boolean isAggregateFn(Function fn) {
    return fn instanceof AggregateFunction
        && ((AggregateFunction) fn).isAggregateFn();
  }

  public static boolean isPercentRankFn(Function fn) {
    return isAnalyticFn(fn, PERCENT_RANK);
  }

  public static boolean isCumeDistFn(Function fn) {
    return isAnalyticFn(fn, CUME_DIST);
  }

  public static boolean isNtileFn(Function fn) {
    return isAnalyticFn(fn, NTILE);
  }

  static private boolean isOffsetFn(Function fn) {
    return isAnalyticFn(fn, LEAD) || isAnalyticFn(fn, LAG);
  }

  static private boolean isMinMax(Function fn) {
    return isAnalyticFn(fn, MIN) || isAnalyticFn(fn, MAX);
  }

  static private boolean isRankingFn(Function fn) {
    return isAnalyticFn(fn, RANK) || isAnalyticFn(fn, DENSERANK) ||
        isAnalyticFn(fn, ROWNUMBER);
  }

  /**
   * Rewrite the following analytic functions:
   * percent_rank(), cume_dist() and ntile()
   *
   * Returns a new Expr if the analytic expr is rewritten, returns null if it's not one
   * that we want to rewrite.
   */
  public static Expr rewrite(AnalyticExpr analyticExpr) {
    Function fn = analyticExpr.getFnCall().getFn();
    if (AnalyticExpr.isPercentRankFn(fn)) {
      return createPercentRank(analyticExpr);
    } else if (AnalyticExpr.isCumeDistFn(fn)) {
      return createCumeDist(analyticExpr);
    } else if (AnalyticExpr.isNtileFn(fn)) {
      return createNtile(analyticExpr);
    }
    return null;
  }

  /**
   * Rewrite percent_rank() to the following:
   *
   * percent_rank() over([partition by clause] order by clause)
   *    = (Rank - 1)/(Count - 1)
   * where,
   *  Rank = rank() over([partition by clause] order by clause)
   *  Count = count() over([partition by clause])
   */
  private static Expr createPercentRank(AnalyticExpr analyticExpr) {
    Preconditions.checkState(
        AnalyticExpr.isPercentRankFn(analyticExpr.getFnCall().getFn()));
    AnalyticExpr rankExpr =
        create("rank", analyticExpr, true, false);
    AnalyticExpr countExpr =
        create("count", analyticExpr, false, false);
    NumericLiteral one = new NumericLiteral(BigInteger.valueOf(1), ScalarType.BIGINT);
    ArithmeticExpr arithmeticRewrite =
        new ArithmeticExpr(ArithmeticExpr.Operator.DIVIDE,
          new ArithmeticExpr(ArithmeticExpr.Operator.SUBTRACT, rankExpr, one),
          new ArithmeticExpr(ArithmeticExpr.Operator.SUBTRACT, countExpr, one));
    return arithmeticRewrite;
  }

  /**
   * Rewrite cume_dist() to the following:
   *
   * cume_dist() over([partition by clause] order by clause)
   *    = ((Count - Rank) + 1)/Count
   * where,
   *  Rank = rank() over([partition by clause] order by clause DESC)
   *  Count = count() over([partition by clause])
   */
  private static Expr createCumeDist(AnalyticExpr analyticExpr) {
    Preconditions.checkState(
        AnalyticExpr.isCumeDistFn(analyticExpr.getFnCall().getFn()));
    AnalyticExpr rankExpr =
        create("rank", analyticExpr, true, true);
    AnalyticExpr countExpr =
        create("count", analyticExpr, false, false);
    NumericLiteral one = new NumericLiteral(BigInteger.valueOf(1), ScalarType.BIGINT);
    ArithmeticExpr arithmeticRewrite =
        new ArithmeticExpr(ArithmeticExpr.Operator.DIVIDE,
          new ArithmeticExpr(ArithmeticExpr.Operator.ADD,
            new ArithmeticExpr(ArithmeticExpr.Operator.SUBTRACT, countExpr, rankExpr),
          one),
        countExpr);
    return arithmeticRewrite;
  }

  /**
   * Rewrite ntile() to the following:
   *
   * ntile(B) over([partition by clause] order by clause)
   *    = floor(min(Count, B) * (RowNumber - 1)/Count) + 1
   * where,
   *  RowNumber = row_number() over([partition by clause] order by clause)
   *  Count = count() over([partition by clause])
   */
  private static Expr createNtile(AnalyticExpr analyticExpr) {
    Preconditions.checkState(
        AnalyticExpr.isNtileFn(analyticExpr.getFnCall().getFn()));
    Expr bucketExpr = analyticExpr.getChild(0);
    AnalyticExpr rowNumExpr =
        create("row_number", analyticExpr, true, false);
    AnalyticExpr countExpr =
        create("count", analyticExpr, false, false);

    List<Expr> ifParams = Lists.newArrayList();
    ifParams.add(
        new BinaryPredicate(BinaryPredicate.Operator.LT, bucketExpr, countExpr));
    ifParams.add(bucketExpr);
    ifParams.add(countExpr);

    NumericLiteral one = new NumericLiteral(BigInteger.valueOf(1), ScalarType.BIGINT);
    ArithmeticExpr minMultiplyRowMinusOne =
        new ArithmeticExpr(ArithmeticExpr.Operator.MULTIPLY,
          new ArithmeticExpr(ArithmeticExpr.Operator.SUBTRACT, rowNumExpr, one),
          new FunctionCallExpr("if", ifParams));
    ArithmeticExpr divideAddOne =
        new ArithmeticExpr(ArithmeticExpr.Operator.ADD,
          new ArithmeticExpr(ArithmeticExpr.Operator.INT_DIVIDE,
            minMultiplyRowMinusOne, countExpr),
        one);
    return divideAddOne;
  }

  /**
   * Create a new Analytic Expr and associate it with a new function.
   * Takes a reference analytic expression and clones the partition expressions and the
   * order by expressions if 'copyOrderBy' is set and optionally reverses it if
   * 'reverseOrderBy' is set. The new function that it will be associated with is
   * specified by fnName.
   */
  private static AnalyticExpr create(String fnName,
      AnalyticExpr referenceExpr, boolean copyOrderBy, boolean reverseOrderBy) {
    FunctionCallExpr fnExpr = new FunctionCallExpr(fnName, new ArrayList<Expr>());
    fnExpr.setIsAnalyticFnCall(true);
    List<OrderByElement> orderByElements = null;
    if (copyOrderBy) {
      if (reverseOrderBy) {
        orderByElements = OrderByElement.reverse(referenceExpr.getOrderByElements());
      } else {
        orderByElements = Lists.newArrayList();
        for (OrderByElement elem: referenceExpr.getOrderByElements()) {
          orderByElements.add(elem.clone());
        }
      }
    }
    AnalyticExpr analyticExpr = new AnalyticExpr(fnExpr,
        Expr.cloneList(referenceExpr.getPartitionExprs()), orderByElements, null);
    return analyticExpr;
  }

  /**
   * Checks that the value expr of an offset boundary of a RANGE window is compatible
   * with orderingExprs (and that there's only a single ordering expr).
   */
  private void checkRangeOffsetBoundaryExpr(AnalyticWindow.Boundary boundary)
      throws AnalysisException {
    Preconditions.checkState(boundary.getType().isOffset());
    if (orderByElements_.size() > 1) {
      throw new AnalysisException("Only one ORDER BY expression allowed if used with "
          + "a RANGE window with PRECEDING/FOLLOWING: " + toSql());
    }
    Expr rangeExpr = boundary.getExpr();
    if (!Type.isImplicitlyCastable(
        rangeExpr.getType(), orderByElements_.get(0).getExpr().getType())) {
      throw new AnalysisException(
          "The value expression of a PRECEDING/FOLLOWING clause of a RANGE window must "
            + "be implicitly convertable to the ORDER BY expression's type: "
            + rangeExpr.toSql() + " cannot be implicitly converted to "
            + orderByElements_.get(0).getExpr().getType().toSql());
    }
  }

  /**
   * Checks offset of lag()/lead().
   */
  void checkOffset(Analyzer analyzer) throws AnalysisException {
    Preconditions.checkState(isOffsetFn(getFnCall().getFn()));
    Preconditions.checkState(getFnCall().getChildren().size() > 1);
    Expr offset = getFnCall().getChild(1);
    Preconditions.checkState(offset.getType().isIntegerType());
    boolean isPosConstant = true;
    if (!offset.isConstant()) {
      isPosConstant = false;
    } else {
      try {
        TColumnValue val = FeSupport.EvalConstExpr(offset, analyzer.getQueryCtx());
        if (TColumnValueUtil.getNumericVal(val) <= 0) isPosConstant = false;
      } catch (InternalException exc) {
        throw new AnalysisException(
            "Couldn't evaluate LEAD/LAG offset: " + exc.getMessage());
      }
    }
    if (!isPosConstant) {
      throw new AnalysisException(
          "The offset parameter of LEAD/LAG must be a constant positive integer: "
            + getFnCall().toSql());
    }
  }

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException {
    if (isAnalyzed_) return;
    fnCall_.analyze(analyzer);
    super.analyze(analyzer);
    type_ = getFnCall().getType();

    for (Expr e: partitionExprs_) {
      if (e.isConstant()) {
        throw new AnalysisException(
            "Expressions in the PARTITION BY clause must not be constant: "
              + e.toSql() + " (in " + toSql() + ")");
      } else if (e.getType().isComplexType()) {
        throw new AnalysisException(String.format("PARTITION BY expression '%s' with " +
            "complex type '%s' is not supported.", e.toSql(),
            e.getType().toSql()));
      }
    }
    for (OrderByElement e: orderByElements_) {
      if (e.getExpr().isConstant()) {
        throw new AnalysisException(
            "Expressions in the ORDER BY clause must not be constant: "
              + e.getExpr().toSql() + " (in " + toSql() + ")");
      } else if (e.getExpr().getType().isComplexType()) {
        throw new AnalysisException(String.format("ORDER BY expression '%s' with " +
            "complex type '%s' is not supported.", e.getExpr().toSql(),
            e.getExpr().getType().toSql()));
      }
    }

    if (getFnCall().getParams().isDistinct()) {
      throw new AnalysisException(
          "DISTINCT not allowed in analytic function: " + getFnCall().toSql());
    }

    // check for correct composition of analytic expr
    Function fn = getFnCall().getFn();
    if (!(fn instanceof AggregateFunction)) {
        throw new AnalysisException(
            "OVER clause requires aggregate or analytic function: "
              + getFnCall().toSql());
    }

    // check for non-analytic aggregate functions
    if (!isAnalyticFn(fn)) {
      throw new AnalysisException(
          String.format("Aggregate function '%s' not supported with OVER clause.",
              getFnCall().toSql()));
    }

    if (isAnalyticFn(fn) && !isAggregateFn(fn)) {
      if (orderByElements_.isEmpty()) {
        throw new AnalysisException(
            "'" + getFnCall().toSql() + "' requires an ORDER BY clause");
      }
      if ((isRankingFn(fn) || isOffsetFn(fn)) && window_ != null) {
        throw new AnalysisException(
            "Windowing clause not allowed with '" + getFnCall().toSql() + "'");
      }
      if (isOffsetFn(fn) && getFnCall().getChildren().size() > 1) {
        checkOffset(analyzer);
        // check the default, which needs to be a constant at the moment
        // TODO: remove this check when the backend can handle non-constants
        if (getFnCall().getChildren().size() > 2) {
          if (!getFnCall().getChild(2).isConstant()) {
            throw new AnalysisException(
                "The default parameter (parameter 3) of LEAD/LAG must be a constant: "
                  + getFnCall().toSql());
          }
        }
      }
      if (isNtileFn(fn)) {
        // TODO: IMPALA-2171:Remove this when ntile() can handle a non-constant argument.
        if (!getFnCall().getChild(0).isConstant()) {
          throw new AnalysisException("NTILE() requires a constant argument");
        }
        // Check if argument value is zero or negative and throw an exception if found.
        try {
          TColumnValue bucketValue =
              FeSupport.EvalConstExpr(getFnCall().getChild(0), analyzer.getQueryCtx());
          Long arg = bucketValue.getLong_val();
          if (arg <= 0) {
            throw new AnalysisException("NTILE() requires a positive argument: " + arg);
          }
        } catch (InternalException e) {
          throw new AnalysisException(e.toString());
        }
      }
    }

    if (window_ != null) {
      if (orderByElements_.isEmpty()) {
        throw new AnalysisException("Windowing clause requires ORDER BY clause: "
            + toSql());
      }
      window_.analyze(analyzer);

      if (!orderByElements_.isEmpty()
          && window_.getType() == AnalyticWindow.Type.RANGE) {
        // check that preceding/following ranges match ordering
        if (window_.getLeftBoundary().getType().isOffset()) {
          checkRangeOffsetBoundaryExpr(window_.getLeftBoundary());
        }
        if (window_.getRightBoundary() != null
            && window_.getRightBoundary().getType().isOffset()) {
          checkRangeOffsetBoundaryExpr(window_.getRightBoundary());
        }
      }
    }

    // check nesting
    if (TreeNode.contains(getChildren(), AnalyticExpr.class)) {
      throw new AnalysisException(
          "Nesting of analytic expressions is not allowed: " + toSql());
    }
    sqlString_ = toSql();

    standardize(analyzer);

    // min/max is not currently supported on sliding windows (i.e. start bound is not
    // unbounded).
    if (window_ != null && isMinMax(fn) &&
        window_.getLeftBoundary().getType() != BoundaryType.UNBOUNDED_PRECEDING) {
      throw new AnalysisException(
          "'" + getFnCall().toSql() + "' is only supported with an "
            + "UNBOUNDED PRECEDING start bound.");
    }

    setChildren();
  }

  /**
   * If necessary, rewrites the analytic function, window, and/or order-by elements into
   * a standard format for the purpose of simpler backend execution, as follows:
   * 1. row_number():
   *    Set a window from UNBOUNDED PRECEDING to CURRENT_ROW.
   * 2. lead()/lag():
   *    Explicitly set the default arguments to for BE simplicity.
   *    Set a window for lead(): UNBOUNDED PRECEDING to OFFSET FOLLOWING.
   *    Set a window for lag(): UNBOUNDED PRECEDING to OFFSET PRECEDING.
   * 3. UNBOUNDED FOLLOWING windows:
   *    Reverse the ordering and window if the start bound is not UNBOUNDED PRECEDING.
   *    Flip first_value() and last_value().
   * 4. first_value():
   *    Set the upper boundary to CURRENT_ROW if the lower boundary is
   *    UNBOUNDED_PRECEDING.
   * 5. Explicitly set the default window if no window was given but there
   *    are order-by elements.
   * 6. FIRST_VALUE without UNBOUNDED PRECEDING gets rewritten to use a different window
   *    and change the function to return the last value. We either set the fn to be
   *    'last_value' or 'first_value_rewrite', which simply wraps the 'last_value'
   *    implementation but allows us to handle the first rows in a partition in a special
   *    way in the backend. There are a few cases:
   *     a) Start bound is X FOLLOWING or CURRENT ROW (X=0):
   *        Use 'last_value' with a window where both bounds are X FOLLOWING (or
   *        CURRENT ROW). Setting the start bound to X following is necessary because the
   *        X rows at the end of a partition have no rows in their window. Note that X
   *        FOLLOWING could be rewritten as lead(X) but that would not work for CURRENT
   *        ROW.
   *     b) Start bound is X PRECEDING and end bound is CURRENT ROW or FOLLOWING:
   *        Use 'first_value_rewrite' and a window with an end bound X PRECEDING. An
   *        extra parameter '-1' is added to indicate to the backend that NULLs should
   *        not be added for the first X rows.
   *     c) Start bound is X PRECEDING and end bound is Y PRECEDING:
   *        Use 'first_value_rewrite' and a window with an end bound X PRECEDING. The
   *        first Y rows in a partition have empty windows and should be NULL. An extra
   *        parameter with the integer constant Y is added to indicate to the backend
   *        that NULLs should be added for the first Y rows.
   */
  private void standardize(Analyzer analyzer) {
    FunctionName analyticFnName = getFnCall().getFnName();

    // Set a window from UNBOUNDED PRECEDING to CURRENT_ROW for row_number().
    if (analyticFnName.getFunction().equals(ROWNUMBER)) {
      Preconditions.checkState(window_ == null, "Unexpected window set for row_numer()");
      window_ = new AnalyticWindow(AnalyticWindow.Type.ROWS,
          new Boundary(BoundaryType.UNBOUNDED_PRECEDING, null),
          new Boundary(BoundaryType.CURRENT_ROW, null));
      resetWindow_ = true;
      return;
    }

    // Explicitly set the default arguments to lead()/lag() for BE simplicity.
    // Set a window for lead(): UNBOUNDED PRECEDING to OFFSET FOLLOWING,
    // Set a window for lag(): UNBOUNDED PRECEDING to OFFSET PRECEDING.
    if (isOffsetFn(getFnCall().getFn())) {
      Preconditions.checkState(window_ == null);

      // If necessary, create a new fn call with the default args explicitly set.
      List<Expr> newExprParams = null;
      if (getFnCall().getChildren().size() == 1) {
        newExprParams = Lists.newArrayListWithExpectedSize(3);
        newExprParams.addAll(getFnCall().getChildren());
        // Default offset is 1.
        newExprParams.add(new NumericLiteral(BigDecimal.valueOf(1)));
        // Default default value is NULL.
        newExprParams.add(new NullLiteral());
      } else if (getFnCall().getChildren().size() == 2) {
        newExprParams = Lists.newArrayListWithExpectedSize(3);
        newExprParams.addAll(getFnCall().getChildren());
        // Default default value is NULL.
        newExprParams.add(new NullLiteral());
      } else  {
        Preconditions.checkState(getFnCall().getChildren().size() == 3);
      }
      if (newExprParams != null) {
        fnCall_ = new FunctionCallExpr(getFnCall().getFnName(),
            new FunctionParams(newExprParams));
        fnCall_.setIsAnalyticFnCall(true);
        fnCall_.analyzeNoThrow(analyzer);
      }

      // Set the window.
      BoundaryType rightBoundaryType = BoundaryType.FOLLOWING;
      if (analyticFnName.getFunction().equals(LAG)) {
        rightBoundaryType = BoundaryType.PRECEDING;
      }
      window_ = new AnalyticWindow(AnalyticWindow.Type.ROWS,
          new Boundary(BoundaryType.UNBOUNDED_PRECEDING, null),
          new Boundary(rightBoundaryType, getOffsetExpr(getFnCall())));
      try {
        window_.analyze(analyzer);
      } catch (AnalysisException e) {
        throw new IllegalStateException(e);
      }
      resetWindow_ = true;
      return;
    }

    if (analyticFnName.getFunction().equals(FIRSTVALUE)
        && window_ != null
        && window_.getLeftBoundary().getType() != BoundaryType.UNBOUNDED_PRECEDING) {
      if (window_.getLeftBoundary().getType() != BoundaryType.PRECEDING) {
        window_ = new AnalyticWindow(window_.getType(), window_.getLeftBoundary(),
            window_.getLeftBoundary());
        fnCall_ = new FunctionCallExpr(new FunctionName("last_value"),
            getFnCall().getParams());
      } else {
        List<Expr> paramExprs = Expr.cloneList(getFnCall().getParams().exprs());
        if (window_.getRightBoundary().getType() == BoundaryType.PRECEDING) {
          // The number of rows preceding for the end bound determines the number of
          // rows at the beginning of each partition that should have a NULL value.
          paramExprs.add(new NumericLiteral(window_.getRightBoundary().getOffsetValue(),
              Type.BIGINT));
        } else {
          // -1 indicates that no NULL values are inserted even though we set the end
          // bound to the start bound (which is PRECEDING) below; this is different from
          // the default behavior of windows with an end bound PRECEDING.
          paramExprs.add(new NumericLiteral(BigInteger.valueOf(-1), Type.BIGINT));
        }

        window_ = new AnalyticWindow(window_.getType(),
            new Boundary(BoundaryType.UNBOUNDED_PRECEDING, null),
            window_.getLeftBoundary());
        fnCall_ = new FunctionCallExpr(new FunctionName("first_value_rewrite"),
            new FunctionParams(paramExprs));
        fnCall_.setIsInternalFnCall(true);
      }
      fnCall_.setIsAnalyticFnCall(true);
      fnCall_.analyzeNoThrow(analyzer);
      type_ = fnCall_.getReturnType();
      analyticFnName = getFnCall().getFnName();
    }

    // Reverse the ordering and window for windows ending with UNBOUNDED FOLLOWING,
    // and and not starting with UNBOUNDED PRECEDING.
    if (window_ != null
        && window_.getRightBoundary().getType() == BoundaryType.UNBOUNDED_FOLLOWING
        && window_.getLeftBoundary().getType() != BoundaryType.UNBOUNDED_PRECEDING) {
      orderByElements_ = OrderByElement.reverse(orderByElements_);
      window_ = window_.reverse();

      // Also flip first_value()/last_value(). For other analytic functions there is no
      // need to also change the function.
      FunctionName reversedFnName = null;
      if (analyticFnName.getFunction().equals(FIRSTVALUE)) {
        reversedFnName = new FunctionName(LASTVALUE);
      } else if (analyticFnName.getFunction().equals(LASTVALUE)) {
        reversedFnName = new FunctionName(FIRSTVALUE);
      }
      if (reversedFnName != null) {
        fnCall_ = new FunctionCallExpr(reversedFnName, getFnCall().getParams());
        fnCall_.setIsAnalyticFnCall(true);
        fnCall_.analyzeNoThrow(analyzer);
      }
      analyticFnName = getFnCall().getFnName();
    }

    // Set the upper boundary to CURRENT_ROW for first_value() if the lower boundary
    // is UNBOUNDED_PRECEDING.
    if (window_ != null
        && window_.getLeftBoundary().getType() == BoundaryType.UNBOUNDED_PRECEDING
        && window_.getRightBoundary().getType() != BoundaryType.PRECEDING
        && analyticFnName.getFunction().equals(FIRSTVALUE)) {
      window_.setRightBoundary(new Boundary(BoundaryType.CURRENT_ROW, null));
    }

    // Set the default window.
    if (!orderByElements_.isEmpty() && window_ == null) {
      window_ = AnalyticWindow.DEFAULT_WINDOW;
      resetWindow_ = true;
    }
  }

  /**
   * Returns the explicit or implicit offset of an analytic function call.
   */
  private Expr getOffsetExpr(FunctionCallExpr offsetFnCall) {
    Preconditions.checkState(isOffsetFn(getFnCall().getFn()));
    if (offsetFnCall.getChild(1) != null) return offsetFnCall.getChild(1);
    // The default offset is 1.
    return new NumericLiteral(BigDecimal.valueOf(1));
  }

  /**
   * Keep fnCall_, partitionExprs_ and orderByElements_ in sync with children_.
   */
  private void syncWithChildren() {
    int numArgs = fnCall_.getChildren().size();
    for (int i = 0; i < numArgs; ++i) {
      fnCall_.setChild(i, getChild(i));
    }
    int numPartitionExprs = partitionExprs_.size();
    for (int i = 0; i < numPartitionExprs; ++i) {
      partitionExprs_.set(i, getChild(numArgs + i));
    }
    for (int i = 0; i < orderByElements_.size(); ++i) {
      orderByElements_.get(i).setExpr(getChild(numArgs + numPartitionExprs + i));
    }
  }

  /**
   * Populate children_ from fnCall_, partitionExprs_, orderByElements_
   */
  private void setChildren() {
    getChildren().clear();
    addChildren(fnCall_.getChildren());
    addChildren(partitionExprs_);
    for (OrderByElement e: orderByElements_) {
      addChild(e.getExpr());
    }
    if (window_ != null) {
      if (window_.getLeftBoundary().getExpr() != null) {
        addChild(window_.getLeftBoundary().getExpr());
      }
      if (window_.getRightBoundary() != null
          && window_.getRightBoundary().getExpr() != null) {
        addChild(window_.getRightBoundary().getExpr());
      }
    }
  }

  @Override
  protected void resetAnalysisState() {
    super.resetAnalysisState();
    fnCall_.resetAnalysisState();
    if (resetWindow_) window_ = null;
    resetWindow_ = false;
    // sync with children, now that they've been reset
    syncWithChildren();
  }

  @Override
  protected Expr substituteImpl(ExprSubstitutionMap smap, Analyzer analyzer)
      throws AnalysisException {
    Expr e = super.substituteImpl(smap, analyzer);
    if (!(e instanceof AnalyticExpr)) return e;
    // Re-sync state after possible child substitution.
    ((AnalyticExpr) e).syncWithChildren();
    return e;
  }
}
