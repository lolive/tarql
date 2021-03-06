package org.deri.tarql;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingHashMap;
import org.apache.jena.util.iterator.ClosableIterator;

import com.opencsv.CSVReader;


/**
 * Parses a CSV file presented as a {@link Reader}, and delivers
 * results as an iterator of {@link Binding}s. Also provides
 * access to the variable names (which may come from row 1 or
 * could be auto-generated).
 * <p>
 * Adds a <code>ROWNUM</code> column with the number of the
 * row.
 */
public class CSVParser implements ClosableIterator<Binding> {

	private final Map<String, UnflattenRule> unflattenRules;

	public static String getColumnName(int i) {
		String var = "";
		do {
			var = alphabet.charAt(i % alphabet.length()) + var;
			i = i / alphabet.length() - 1;
		} while (i >= 0);
		return var;
	}

	private final static String alphabet = "abcdefghijklmnopqrstuvwxyz";

	private final Reader reader;
	private final boolean varsFromHeader;
	private final char delimiter;
	private final Character quote;
	private final Character escape;
	private final List<Var> vars = new ArrayList<Var>();
	private int rownum;

	private ArrayList<Binding> bindings = new ArrayList<>();
	private CSVReader csv;

	/**
	 * @param reader
	 *            Reader over the contents of a CSV file
	 * @param varsFromHeader
	 *            If true, use values of first row as column names
	 * @param delimiter
	 *            The delimiter character to use for separating entries (e.g., ',' or ';' or '\t'), or <code>null</code> for default
	 * @param quote
	 *            The quote character used to quote values (typically double or single quote), or <code>null</code> for default
	 * @param escape
	 *            The escape character for quotes and delimiters, or <code>null</code> for none 
	 * @throws IOException if an I/O error occurs while reading from the input
	 */
	public CSVParser(Reader reader, boolean varsFromHeader, Character delimiter, Character quote, Character escape, Map<String, UnflattenRule> unflattenRules)
			throws IOException {
		this.reader = reader;
		this.varsFromHeader = varsFromHeader;
		this.delimiter = delimiter == null ? ',' : delimiter;
		// OpenCSV insists on a quote character
		this.quote = quote == null ? '\0' : quote;
		// OpenCSV insists on an escape character
		this.escape = escape == null ? '\0' : escape;
		this.unflattenRules = unflattenRules;
		init();
	}

	private Var toVar(String s) {
		if (s == null)
			return null;
        s = s.trim().replace(" ", "_").replace("-", "_").replace("?", "_").replace("%", "_").replace("(", "_").replace(")", "_");
		if ("".equals(s))
			return null;
		// FIXME: Handle other characters not allowed in Vars
		return Var.alloc(s);
	}

	private boolean isEmpty(String[] row) {
		for (int i = 0; i < row.length; i++) {
			if (!isUnboundValue(row[i]))
				return false;
		}
		return true;
	}

	/**
	 * Checks whether a string taken from a CSV cell is considered an unbound
	 * SPARQL value
	 */
	private boolean isUnboundValue(String value) {
		return value == null || "".equals(value);
	}

	private ArrayList<Binding> toBindings(String[] row) {
		ArrayList<Binding> allBindings = new ArrayList<>();
		BindingHashMap rowToBindings = new BindingHashMap();
		// Add current row number as ?ROWNUM
		rowToBindings.add(TarqlQuery.ROWNUM, NodeFactory.createLiteral(
				Integer.toString(rownum), XSDDatatype.XSDinteger));
		allBindings.add(rowToBindings);

		for (int i = 0; i < row.length; i++) {
			if (isUnboundValue(row[i]))
				continue;
			Var var = getVar(i);
			List<String> values = new ArrayList<>();
			if(unflattenRules!=null && !unflattenRules.isEmpty() && unflattenRules.containsKey(var.getVarName())){
				values.addAll(unflattenRules.get(var.getVarName()).apply(row[i]));
			} else {
				values.add(row[i]);
			}
			ArrayList<Binding> allBindings2 = new ArrayList<>();
			for (String value : values) {
				for(Binding binding: allBindings) {
					BindingHashMap b = new BindingHashMap();
					b.addAll(binding);
					b.add(var, bindingValue(value));
					allBindings2.add(b);
				}
			}
			allBindings = allBindings2;
		}

		return allBindings;
	}

	private Node bindingValue(String s) {
		return NodeFactory.createLiteral(sanitizeString(s));
	}

	/**
	 * Remove/replace weird characters known to cause problems in RDF toolkits.
	 */
	private String sanitizeString(String s) {
		// ASCII 10h, "Data Link Escape", causes parse failure in Turtle
		// in Virtuoso 7.0.0
		return s.replace((char) 0x10, (char) 0xFFFD);
	}

	private Var getVar(int column) {
		if (vars.size() < column) {
			getVar(column - 1);
		}
		if (vars.size() == column) {
			Var var = Var.alloc(getColumnName(column));
			while (vars.contains(var)) {
				var = Var.alloc("_" + var.getName());
			}
			vars.add(var);
		}
		return vars.get(column);
	}

	@Override
	public boolean hasNext() {
		ArrayList<Binding> b = bindings();
		return b!=null && !b.isEmpty();
	}

	@Override
	public Binding next() {
		Binding next = bindings().remove(0);
		return next;
	}

	private ArrayList<Binding> bindings() {
		if(bindings==null || bindings.isEmpty()) {
			String[] row;
			try {
				while ((row = csv.readNext()) != null) {
					// Skip rows without data
					if (isEmpty(row))
						continue;
					bindings = toBindings(row);
					rownum++;
					break;
				}
			} catch (IOException e) {
				throw new TarqlException(e);
			}
		}
		return bindings;
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException(
				"Remove is not supported. It is a read-only iterator");
	}

	@Override
	public void close() {
		try {
			csv.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public List<Var> getVars() {
		List<Var> varsWithRowNum = new ArrayList<Var>(vars);
		varsWithRowNum.add(TarqlQuery.ROWNUM);
		return varsWithRowNum;
	}
	
	private void init() throws IOException {
		String[] row;
		csv = new CSVReader(reader, delimiter, quote, escape);
		if (varsFromHeader) {
			while ((row = csv.readNext()) != null) {
				boolean foundValidColumnName = false;
				for (int i = 0; i < row.length; i++) {
					if (toVar(row[i]) == null)
						continue;
					foundValidColumnName = true;
				}
				// If row was empty or didn't contain anything usable
				// as column name, then try next row
				if (!foundValidColumnName)
					continue;
				for (int i = 0; i < row.length; i++) {
					Var var = toVar(row[i]);
					if (var == null || vars.contains(var)
							|| var.equals(TarqlQuery.ROWNUM)) {
						getVar(i);
					} else {
						vars.add(var);
					}
				}
				break;
			}
		}
		rownum = 0;
		//next();
	}


}
