package eu.stratosphere.sql.relOpt;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.jexl2.Expression;
import org.apache.commons.jexl2.JexlContext;
import org.apache.commons.jexl2.JexlEngine;
import org.apache.commons.jexl2.MapContext;
import org.eigenbase.rel.FilterRelBase;
import org.eigenbase.rel.RelNode;
import org.eigenbase.relopt.RelOptCluster;
import org.eigenbase.relopt.RelTraitSet;
import org.eigenbase.reltype.RelDataType;
import org.eigenbase.rex.RexLocalRef;
import org.eigenbase.rex.RexNode;

import com.google.common.base.Preconditions;

import eu.stratosphere.api.common.operators.Operator;
import eu.stratosphere.api.java.record.functions.MapFunction;
import eu.stratosphere.api.java.record.operators.MapOperator;
import eu.stratosphere.configuration.Configuration;
import eu.stratosphere.types.JavaValue;
import eu.stratosphere.types.Record;
import eu.stratosphere.types.Value;
import eu.stratosphere.util.Collector;

public class StratosphereSqlFilter	extends FilterRelBase implements StratosphereRel {
	public StratosphereSqlFilter(RelOptCluster cluster, RelTraitSet traits,
			RelNode child, RexNode condition) {
		super(cluster, traits, child, condition);
		Preconditions.checkArgument(getConvention() == CONVENTION);
	}

	@Override
	public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
		System.err.println("StratosphereSqlFilter.copy()");
		return new StratosphereSqlFilter(getCluster(), traitSet, sole(inputs), getCondition());
	}
	
	public static class StratosphereSqlFilterMapOperator extends MapFunction {
		private static final long serialVersionUID = 1L;
		private static final JexlEngine JEXL = new JexlEngine();
		
		static {
			JEXL.setCache(512);
			JEXL.setLenient(false);
			JEXL.setSilent(false);
		}
		
		private String exprStr;
		private transient Expression expr;
		private transient JexlContext context;
		private List<StratosphereRelUtils.ExprVar> variables;
		public StratosphereSqlFilterMapOperator(String expression, List<StratosphereRelUtils.ExprVar> vars) {
			this.exprStr = expression;
			this.variables = vars;
		}
		@Override
		public void open(Configuration parameters) throws Exception {
			super.open(parameters);
			this.expr = JEXL.createExpression(exprStr);
			this.context = new MapContext();
		}

		@Override
		public void map(Record record, Collector<Record> out) throws Exception {
			// set values for expression
			for(StratosphereRelUtils.ExprVar var : variables) {
				Value val = record.getField(var.positionInRecord, var.type);
				context.set(var.varName, ( (JavaValue<?>) val).getObjectValue() );
			}
			// evaluate.
			if((Boolean) this.expr.evaluate(context)) {
				out.collect(record);
			}
		}
		
	}
	
	@Override
	public Operator getStratosphereOperator() {
		Operator inputOp = StratosphereRelUtils.openSingleInputOperator(getInputs());
		RexNode cond = getCondition();
		
		List<RexLocalRef> projects;
		RelDataType outputRowType;
		
		String jexlExpr = StratosphereRelUtils.convertRexCallToJexlExpr(cond);
		List<StratosphereRelUtils.ExprVar> vars = new ArrayList<StratosphereRelUtils.ExprVar>(); 
		StratosphereRelUtils.getExprVarsFromRexCall(cond,vars);
		
		Operator filter = MapOperator.builder(new StratosphereSqlFilterMapOperator(jexlExpr, vars) )
									.input(inputOp)
									.name(condition.toString())
									.build();
		return filter;
	}

}
