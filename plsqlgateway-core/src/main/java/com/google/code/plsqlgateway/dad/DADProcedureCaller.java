package com.google.code.plsqlgateway.dad;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

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
	private EntityConfig genconfig;
    private boolean isdocument;
    private boolean unauthorized;
    private String[] lines;
    private HttpServletRequest request;
    @SuppressWarnings("unchecked")
	private Map parameterMap;
    @SuppressWarnings("unchecked")
	private Enumeration parameterNames;
    private EntityConfig dadConfig;
    private String pathInfo;
    private String calledProc;
    private Object[] values;
    private int[] types;
    private String[][] cgienv;
    
	@SuppressWarnings("unchecked")
	public DADProcedureCaller(String pathInfo, Enumeration parameterNames, Map parameterMap, HttpServletRequest request, EntityConfig dadConfig, String[][] cgienv, EntityConfig intconfig, EntityConfig genconfig)
	{
		this.parameterNames= parameterNames;
		this.parameterMap= parameterMap;
		this.request= request;
		this.dadConfig= dadConfig;
		this.pathInfo= pathInfo;
		this.cgienv= cgienv;
		this.intconfig= intconfig;
		this.genconfig= genconfig;
	}
	
	private void setVcArr(OracleCallableStatement stmt, int parameterIndex, String[] arrayData)
	throws Exception
	{
		  stmt.setPlsqlIndexTable(parameterIndex, arrayData, arrayData.length, arrayData.length, OracleTypes.VARCHAR, 32767);
	}
	
	private void _call(Connection conn, boolean describe)
	throws Exception
	{
	    boolean flexible= pathInfo.startsWith("/!");
	    String pathAlias= dadConfig.getParameter("path-alias");
	    boolean alias= pathInfo.startsWith("/"+pathAlias+"/");

	    String sql= getSQL(flexible, alias, describe, conn);
	    
	    OracleCallableStatement stmt= (OracleCallableStatement) conn.prepareCall(sql);
	    
	    int parameterIndex= 1;
	    stmt.setInt(parameterIndex++, cgienv[0].length);
	    setVcArr(stmt, parameterIndex++, cgienv[0]);
	    setVcArr(stmt, parameterIndex++, cgienv[1]);

	    if (alias)
	    {
	    	stmt.setString(parameterIndex++, pathInfo.substring(pathInfo.indexOf('/', 1)));
	    	
	    	if (dadConfig.getBooleanParameter("x-path-alias-flexible"))
	    	{
			    String[][] pars= getParameters();
			    setVcArr(stmt, parameterIndex++, pars[0]);
			    setVcArr(stmt, parameterIndex++, pars[1]);
	    	}
	    }
	    else
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

		    long after= System.currentTimeMillis();
		    
	        if (dadConfig.getBooleanParameter("timed-statistics"))
	        	logger.fatal((after-before)+"ms: "+request.getPathInfo());
		    
	        	int retcode= stmt.getInt(retcodeidx);
	    	
	      	if (retcode==1)
	    	     	isdocument= true;
	      	else
	      	if (retcode==2)
	      		unauthorized= true;
		}
		catch (Exception ex)
		{
			throw ex;
		}
        finally
        {
        	stmt.close();
        }
		
	}
	
	public void call(Connection conn)
	throws Exception
	{
		try
		{
	        _call(conn,false);
		}
		catch (Exception ex)
		{
			try
			{
				if (ex instanceof SQLException)
				{
					SQLException sex= (SQLException)ex;
					
					if (sex.getErrorCode()==6550)
		              _call(conn,true); // ex1
					else
					  throw ex;
				}
				else
					  throw ex;
			}
			catch (Exception ex1)
			{
				dumpCgiEnv(cgienv);
				throw ex1;
			}
		}
        
		
	}
	
	public boolean isAuthorized()
	{
		return !unauthorized;
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
	private String getSQL(boolean flexible, boolean alias, boolean describe, Connection conn)
	throws Exception
	{
		String sqlStmt= intconfig.getSQLstmt("OWA_CALL");
		
		String procedure= null;
		
		String pathAlias= dadConfig.getParameter("path-alias");
		
		if (alias)
		{
			calledProc= dadConfig.getParameter("path-alias-procedure");
			procedure= calledProc+"(?"+(dadConfig.getBooleanParameter("x-path-alias-flexible") ? ",?,?" : "")+")";
		}
		else
		if (flexible)
		{
			calledProc= sqlInjectionIdentifier(pathInfo.substring(2));
		    procedure= calledProc+"(?,?)";
		}
		else
		{
			procedure= sqlInjectionIdentifier(pathInfo.substring(1));	
			calledProc= procedure;
			
			Map<String,Integer> parameterTypes= describeProcedure(calledProc.toUpperCase(),describe,conn);

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
	
	private Map<String,Integer> describeProcedure(String calledProc, boolean describe, Connection conn)
	throws Exception
	{			
	   /* TODO: cache procedure description (IMUO, it is a bad idea to describe procedures at all, 
	    * there is no benefit in defining procedures like apex wwv_flow.accept that need a description
	    * to be called because there is no way for the gateway to deduce it from the request)
	    * 
	    * Avoid this kind of procs, instead use flexible parameter passing! (http://server:port/pls/dad/!pkg.proc)
	    * 
	    */
	
	   if (!describe||!dadConfig.getBooleanParameter("describe-procedure"))
		   return EMPTY_DESCRIBE_MAP;
		   
       String[] parts= calledProc.split("\\.");
       
       if (parts.length==1)
       {
    	   // procedure
    	   // procedure synonym
    	   
		   String proc= parts[0];
    	   
    	   if (existsProcedure(null, null, proc, conn))
        	   return getProcedureColumns(null, null, proc, conn);
    	   else
    	   {
    		   proc= translateSynonym(conn,parts);
    		   return describeProcedure(proc, describe, conn);
    	   }
       }
       else
       if (parts.length==2)
       {
    	   // package.procedure
    	   // schema.procedure
    	   // (package synonym).procedure
    	   // schema.(procedure synonym)
		   String schema= parts[0];
		   String pkg= parts[0];
		   
		   String proc= parts[1];
    	   
    	   
    	   // package.procedure
    	   if (existsProcedure(null, pkg, proc, conn))
        	   return getProcedureColumns(null, pkg, proc, conn);
    	   else
       	   {
        	   // schema.procedure
        	   if (existsProcedure(schema, null, proc, conn))
            	   return getProcedureColumns(schema, null, proc, conn);
        	   else
        	   {
        		   proc= translateSynonym(conn,parts);
        		   return describeProcedure(proc, describe, conn);
        	   }
       	   }
       		         	      
       }
       else
       if (parts.length==3)
       {
		   // schema.package.procedure
		   // schema.(package synonym).procedure
		   String schema= parts[0];
		   String pkg= parts[1];
		   String proc= parts[2];
    	   
		   // schema.package.procedure
    	   if (existsProcedure(schema, pkg, proc, conn))
        	   return getProcedureColumns(schema, pkg, proc, conn);
    	   else
    	   {
    		   proc= translateSynonym(conn,parts);
    		   return describeProcedure(proc, describe, conn);        		   
    	   }
       }
       else
    	   throw new RuntimeException("Bad procedure '"+calledProc+"'");
	}
	
	private boolean existsProcedure(String schema, String pkg, String proc, Connection conn) 
	throws Exception
	{
		PreparedStatement stmt= null;
		
		if (schema==null&&pkg==null)
		{
           stmt= conn.prepareStatement(intconfig.getSQLstmt("EXISTS_PROC"));
           stmt.setString(1, proc);
		}
		else
		if (schema==null)
		{
           stmt= conn.prepareStatement(intconfig.getSQLstmt("EXISTS_PKG_PROC"));
           stmt.setString(1, proc);
           stmt.setString(2, pkg);
		}
		else
		if (pkg==null)
		{
           stmt= conn.prepareStatement(intconfig.getSQLstmt("EXISTS_SCHEMA_PROC"));
           stmt.setString(1, proc);
           stmt.setString(2, schema);
		}
		else
		{
           stmt= conn.prepareStatement(intconfig.getSQLstmt("EXISTS_SCHEMA_PKG_PROC"));
           stmt.setString(1, proc);
           stmt.setString(2, pkg);
           stmt.setString(3, schema);
		}
		
		ResultSet rs= stmt.executeQuery();
		
		boolean exists= rs.next();
		
		rs.close();
		stmt.close();
		
		return exists;
	}

	private Map<String,Integer> getProcedureColumns(String schema, String pkg, String proc, Connection conn) 
	throws Exception
	{
		PreparedStatement stmt= null;
		
		if (schema==null&&pkg==null)
		{
           stmt= conn.prepareStatement(intconfig.getSQLstmt("PROC_COLUMNS"));
           stmt.setString(1, proc);
		}
		else
		if (schema==null)
		{
           stmt= conn.prepareStatement(intconfig.getSQLstmt("PKG_PROC_COLUMNS"));
           stmt.setString(1, proc);
           stmt.setString(2, pkg);
		}
		else
		if (pkg==null)
		{
           stmt= conn.prepareStatement(intconfig.getSQLstmt("SCHEMA_PROC_COLUMNS"));
           stmt.setString(1, proc);
           stmt.setString(2, schema);
		}
		else
		{
           stmt= conn.prepareStatement(intconfig.getSQLstmt("SCHEMA_PKG_PROC_COLUMNS"));
           stmt.setString(1, proc);
           stmt.setString(2, pkg);
           stmt.setString(3, schema);
		}
		
		ResultSet rs= stmt.executeQuery();
		Map<String,Integer> descriptor= new HashMap<String, Integer>();
		
		while (rs.next())
		{
		  String dataType= rs.getString(2);
		  descriptor.put(rs.getString(1), 
				         ("PL/SQL TABLE".equals(dataType) ? Types.OTHER : Types.VARCHAR));
		}
		
		rs.close();
		stmt.close();
		
		return descriptor;
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
		
		stmt.close();
		
	    return retval;
	}

	@SuppressWarnings("unchecked")
	private String[][] getParameters()
	throws Exception
	{
		Map m= parameterMap;
		ArrayList<String> names= new ArrayList<String>();
		ArrayList<String> values= new ArrayList<String>();
        
		while (parameterNames.hasMoreElements())
		{
			String name= (String) parameterNames.nextElement();
			
			Object value= m.get(name);
			
			if (value instanceof String[])
			{
				String[] vals= (String[])value;
				
				for (String v: vals)
				{
					names.add(name);
					values.add(v);
				}
			}
			else
			{
				names.add(name);
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
		if (tomatch.matches(genconfig.getParameter("sql-injection-regexp")))
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
