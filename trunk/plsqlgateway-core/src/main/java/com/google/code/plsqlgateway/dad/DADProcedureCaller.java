package com.google.code.plsqlgateway.dad;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;

import com.google.code.plsqlgateway.servlet.SQLInjectionException;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleTypes;
import oracle.sql.BFILE;
import oracle.sql.BLOB;
import com.google.code.eforceconfig.EntityConfig;

public class DADProcedureCaller
{
    private static final HashMap<String,Integer> EMPTY_DESCRIBE_MAP= new HashMap<String,Integer>();
    private static final String[][] EMPTY_PARAMETER_MAP= new String[][]{new String[]{""},new String[]{""}};
    
	private static Logger logger= Logger.getLogger(DADProcedureCaller.class);
    
	private EntityConfig intconfig;
    private boolean isdocument;
    private String[] lines;
    private HttpServletRequest request;
    @SuppressWarnings("unchecked")
	private Map parameterMap;
    private EntityConfig dadConfig;
    private String pathInfo;
    private String calledProc;
    private Object[] values;
    private int[] types;
    private String[][] cgienv;
    
	@SuppressWarnings("unchecked")
	public DADProcedureCaller(String pathInfo, Map parameterMap, HttpServletRequest request, EntityConfig dadConfig, String[][] cgienv, EntityConfig intconfig)
	{
		this.parameterMap= parameterMap;
		this.request= request;
		this.dadConfig= dadConfig;
		this.pathInfo= pathInfo;
		this.cgienv= cgienv;
		this.intconfig= intconfig;
	}
	
	private void setVcArr(OracleCallableStatement stmt, int parameterIndex, String[] arrayData)
	throws Exception
	{
		  stmt.setPlsqlIndexTable(parameterIndex, arrayData, arrayData.length, arrayData.length, OracleTypes.VARCHAR, 32767);
	}		
	
	public void call(Connection conn)
	throws Exception
	{
		
	    boolean flexible= pathInfo.startsWith("/!");

	    String sql= getSQL(flexible, conn);
	    
	    OracleCallableStatement stmt= (OracleCallableStatement) conn.prepareCall(sql);
	    
	    int parameterIndex= 1;
	    stmt.setInt(parameterIndex++, cgienv[0].length);
	    setVcArr(stmt, parameterIndex++, cgienv[0]);
	    setVcArr(stmt, parameterIndex++, cgienv[1]);

	    if (flexible) // flexible parameter passing
	    {
		    String[][] pars= getParameters();
		    setVcArr(stmt, parameterIndex++, pars[0]);
		    setVcArr(stmt, parameterIndex++, pars[1]);
	    }
	    else
	    {
            int idx= 0;
            
			for (Object val: values)
			{
				if (val instanceof String[])
				{
				   String[] aval= (String[])val;
				   
				   if (types[idx]==Types.OTHER)
					   setVcArr(stmt, parameterIndex++, aval);
				   else
					   stmt.setString(parameterIndex++, aval[0]);
					   
				}
				else
				   stmt.setString(parameterIndex++, (String)val);
				
				idx++;
			}
	    }
	    
	    int docidx= parameterIndex++;

	    stmt.registerOutParameter(docidx, OracleTypes.VARCHAR);
	    int retcodeidx= parameterIndex++;

	    stmt.registerOutParameter(retcodeidx, OracleTypes.INTEGER);
	    
		long before= System.currentTimeMillis();
        
		try
		{
	       stmt.execute();
		}
		catch (Exception ex)
		{
			dumpCgiEnv(cgienv);
			throw ex;
		}

	    long after= System.currentTimeMillis();
	    
        if (dadConfig.getBooleanParameter("timed-statistics"))
        	logger.fatal((after-before)+"ms: "+request.getPathInfo());
	    
    	int retcode= stmt.getInt(retcodeidx);
    	
    	if (retcode==1)
    		isdocument= true;

    	stmt.close();
	}
	
	public int fetch(Connection conn)
	throws Exception
	{
	    OracleCallableStatement stmt= (OracleCallableStatement) conn.prepareCall(intconfig.getSQLstmt("OWA_FETCH"));
        
	    int ROWS4FETCH= 50;
	    stmt.setInt(1, ROWS4FETCH); // rows for fetch
	    stmt.registerOutParameter(1, OracleTypes.INTEGER);
	    stmt.registerIndexTableOutParameter(2, 50, OracleTypes.VARCHAR, 256);
	    
	    stmt.execute();
	    
	    lines= (String[])stmt.getPlsqlIndexTable(2); 
	    
	    int retval= stmt.getInt(1);
	    
	    stmt.close();
	    
		return retval;
	}
	
	public boolean isDocument()
	{
		return isdocument;
	}
	
	public InputStream getDocument(Connection conn)
	throws Exception
	{
	    final OracleCallableStatement stmt= (OracleCallableStatement) conn.prepareCall(intconfig.getSQLstmt("OWA_BLOB"));
        
	    stmt.registerOutParameter(1, OracleTypes.BLOB);
	    
	    stmt.execute();
	    
	    final BLOB blob= stmt.getBLOB(1);
	    
	    if (blob!=null)
	    {
	      blob.open(BLOB.MODE_READONLY);
	      final InputStream in= blob.getBinaryStream(1);

		    return new InputStream() 
		    {
				
				public int read()
				throws IOException 
				{
					return in.read();
				}
				
				public int read(byte[] b)
				throws IOException
				{
					return in.read(b);
				}
				
			    public void close() 
			    throws IOException
			    {
			    	try
			    	{
				    	in.close();
				    	blob.close();
			    	}
			    	catch (Exception ex)
			    	{}
			    	
			    	
			    	try
			    	{
				    	stmt.close();
			    	}
			    	catch (Exception ex)
			    	{
			    		throw new RuntimeException(ex);
			    	}
			    }

			};
	    }
	    else
	    {
	    	try
	    	{
		    	stmt.close();
	    	}
	    	catch (Exception ex)
	    	{
	    		throw new RuntimeException(ex);
	    	}
	    	
		    final OracleCallableStatement stmt2= (OracleCallableStatement) conn.prepareCall(intconfig.getSQLstmt("OWA_BFILE"));
            
		    stmt2.registerOutParameter(1, OracleTypes.BFILE);
		    
		    stmt2.execute();
		    
		    final BFILE bfile= stmt2.getBFILE(1);
	    	
		    bfile.open(BLOB.MODE_READONLY);
		    final InputStream in= bfile.getBinaryStream(1);

			    return new InputStream() 
			    {
					
					public int read()
					throws IOException 
					{
						return in.read();
					}
					
					public int read(byte[] b)
					throws IOException
					{
						return in.read(b);
					}
					
				    public void close() 
				    throws IOException
				    {
				    	try
				    	{
					    	in.close();
					    	bfile.close();
				    	}
				    	catch (Exception ex)
				    	{}
				    	
				    	
				    	try
				    	{
				    		stmt2.close();
				    	}
				    	catch (Exception ex)
				    	{
				    		throw new RuntimeException(ex);
				    	}
				    }
	
				};
		    
	    }
	}
	
	public String[] getLines()
	{
		return lines;
	}
	
	@SuppressWarnings("unchecked")
	private String getSQL(boolean flexible,Connection conn)
	throws Exception
	{
		String sqlStmt= intconfig.getSQLstmt("OWA_CALL");
		
		String procedure= null;
		
		if (flexible)
		{
			calledProc= sqlInjectionIdentifier(pathInfo.substring(2));
		    procedure= calledProc+"(?,?)";
		}
		else
		{
			procedure= sqlInjectionIdentifier(pathInfo.substring(1));	
			calledProc= procedure;
			
			Map<String,Integer> parameterTypes= describeProcedure(calledProc,conn);

			String pars= "";
			Set<Map.Entry<String,Object>> s= parameterMap.entrySet();
			Iterator<Map.Entry<String,Object>> i= s.iterator();
			values= new Object[s.size()];
			types= new int[s.size()];
			
			int idx= 0;
			while (i.hasNext())
			{
				Map.Entry<String,Object> e= i.next();
				values[idx]= e.getValue();
				Integer type= parameterTypes.get(e.getKey().toUpperCase());
				types[idx]= (type==null ? Types.VARCHAR : type);
				pars+=", "+sqlInjectionIdentifier(e.getKey())+" => ?";
				idx++;
			}
			
			if (pars.length()>2)
			  procedure+= "("+pars.substring(2)+")";
			
		}
		
		sqlStmt= sqlStmt.replaceFirst("#procedure#", procedure);

		String requestValidationFunction= dadConfig.getParameter("request-validation-function");

		sqlStmt= sqlStmt.replaceFirst("#request-validation-function#", 
				   (requestValidationFunction!=null ? 
						   requestValidationFunction+"('"+calledProc+"')" : "true"));

		String beforeProcedure= dadConfig.getParameter("before-procedure");

		sqlStmt= sqlStmt.replaceFirst("#before-procedure#", 
				   (beforeProcedure!=null ? 
						   beforeProcedure : "null"));
		
		String afterProcedure= dadConfig.getParameter("after-procedure");
		
		sqlStmt= sqlStmt.replaceFirst("#after-procedure#", 
				   (afterProcedure!=null ? 
						   afterProcedure : "null"));

		if (logger.isDebugEnabled())
			logger.debug("sqlStmt: "+sqlStmt);

		return sqlStmt;
	}
	
	private Map<String,Integer> describeProcedure(String calledProc, Connection conn)
	throws Exception
	{			
		
	   if (!dadConfig.getBooleanParameter("describe-procedure"))
		   return EMPTY_DESCRIBE_MAP;
		   
       String[] parts= calledProc.split("\\.");
       
       ResultSet rs= null;
       
       if (parts.length==1)
       {
    	   // procedure
    	   // procedure synonym
    	   
		   String proc= parts[0].toUpperCase();
    	   rs= conn.getMetaData().getProcedures("", "", proc);
    	   
    	   if (!rs.next())
    	   {
    		   rs.close();
    		   proc= translateSynonym(conn,parts);
    		   return describeProcedure(proc, conn);
    	   }
    	   else
    	   {
    		   rs.close();
    		   
        	   rs= conn.getMetaData().getProcedureColumns("", "", proc, "%");
    	   }
       }
       else
       if (parts.length==2)
       {
    	   // package.procedure
    	   // schema.procedure
    	   // (package synonym).procedure
    	   // schema.(procedure synonym)
    	   
    	   rs= conn.getMetaData().getProcedures(parts[0].toUpperCase(), "", parts[1].toUpperCase());
    	   
    	   if (rs.next())
    	   {
    		   rs.close();
    		   
        	   // package.procedure
        	   rs= conn.getMetaData().getProcedureColumns(parts[0].toUpperCase(), "", parts[1].toUpperCase(), "%");
    	   }
    	   else
       	   {
    		   rs.close();
    		   
        	   rs= conn.getMetaData().getProcedures("", parts[0].toUpperCase(), parts[1].toUpperCase());

        	   if (rs.next())
        	   {
        		   rs.close();
        		   
       		       // schema.procedure
        	       rs= conn.getMetaData().getProcedureColumns("", parts[0].toUpperCase(), parts[1].toUpperCase(), "%");
        	   }
        	   else
        	   {
        		   rs.close();
        		   
        		   String proc= translateSynonym(conn,parts);
        		   return describeProcedure(proc, conn);
        	   }
       	   }
       		         	      
       }
       else
       if (parts.length==3)
       {
		       // schema.package.procedure
		       // schema.(package synonym).procedure
    	   
    	   rs= conn.getMetaData().getProcedures(parts[1].toUpperCase(), parts[0].toUpperCase(), parts[2].toUpperCase());
    	   
    	   if (rs.next())
    	   {
    		   rs.close();
    	   
   		       // schema.package.procedure
      	       rs= conn.getMetaData().getProcedureColumns(parts[1].toUpperCase(), parts[0].toUpperCase(), parts[2].toUpperCase(), "%");
    	   }
    	   else
    	   {
    		   rs.close();

    		   String proc= translateSynonym(conn,parts);
    		   return describeProcedure(proc, conn);        		   
    	   }
    	   
       }
       
       Map<String,Integer> pt= new HashMap<String,Integer>();
       
       while (rs.next())
           pt.put(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"));
    	   
       return pt;
	}
	
	private String[] toupper(String[] vals)
	{
		String[] ret= new String[vals.length];
		
		for (int i = 0; i < ret.length; i++) 
           ret[i]= vals[i].toUpperCase();
		
		return ret;
	}

	private String translateSynonym(Connection conn, String[] parts)
	throws Exception
	{
 	    // procedure synonym
 	    // (package synonym).procedure
 	    // schema.(procedure synonym)
	    // schema.(package synonym).procedure
		
		OracleCallableStatement stmt= (OracleCallableStatement)conn.prepareCall(intconfig.getSQLstmt("TRANSLATE_SYNONYM"));
		
		setVcArr(stmt, 1, toupper(parts));			
		
		stmt.registerOutParameter(2, OracleTypes.VARCHAR);
		
		stmt.execute();
		
		String retval= stmt.getString(2);  
		
	    return retval;
	}

	@SuppressWarnings("unchecked")
	private String[][] getParameters()
	throws Exception
	{
		Map m= parameterMap;
		ArrayList<String> names= new ArrayList<String>();
		ArrayList<String> values= new ArrayList<String>();
        
		Iterator i= m.entrySet().iterator();
		
		while (i.hasNext())
		{
			Entry e= (Entry) i.next();
			
			Object value= e.getValue();
			
			if (value instanceof String[])
			{
				String[] vals= (String[])value;
				
				for (String v: vals)
				{
					names.add((String) e.getKey());
					values.add(v);
				}
			}
			else
			{
				names.add((String) e.getKey());
				values.add((String) value);
			}
		}
		
		String[][] retval= new String[2][names.size()];
		retval[0]= names.toArray(new String[0]);
		retval[1]= values.toArray(new String[0]);
		
		// bug in owa utils we need to pass at least one parameter in some cases
		if (retval[0].length==0)
			retval= EMPTY_PARAMETER_MAP;
		
		return retval;
	}
    	
	private void dumpCgiEnv(String[][] cgi)
	{
		logger.error("CGI ENV:");
		for (int i = 0; i < cgi[0].length; i++) 
		   logger.error("    "+cgi[0][i]+": "+cgi[1][i]);
	}

	@SuppressWarnings("unchecked")
	private String sqlInjectionIdentifier(String identifier)
	throws SQLInjectionException
	{
		String tomatch= identifier.toLowerCase();
		if (tomatch.matches(intconfig.getParameter("sql-injection-regexp")))
		{
		   ArrayList<String> exclusionList= dadConfig.getListParameter("exclusion-list");
		   
		   for (String regex: exclusionList)
		   {
			   if (tomatch.matches(regex))
				 throw new SQLInjectionException(identifier+" matches exclusion regexp /"+regex+"/");
		   }
			
		   return identifier;
		}
		else
		 throw new SQLInjectionException(identifier);
	}

}
