package com.google.code.plsqlgateway.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleResultSet;
import oracle.jdbc.OracleTypes;
import oracle.sql.BLOB;

import org.apache.commons.fileupload.FileUpload;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.RequestContext;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.servlet.ServletRequestContext;
import org.apache.log4j.Logger;

import sun.misc.BASE64Decoder;

import com.google.code.eforceconfig.EntityConfig;
import com.google.code.plsqlgateway.config.Configuration;
import com.google.code.plsqlgateway.dad.DADProcedureCaller;
import com.google.code.plsqlgateway.servlet.upload.OracleFileItem;
import com.google.code.plsqlgateway.servlet.upload.OracleFileItemFactory;

/**
 * Servlet implementation class PLSQLGatewayServlet
 */
public class PLSQLGatewayServlet extends HttpServlet 
{
	private static final long serialVersionUID = 1L;
	private static final Logger logger= Logger.getLogger(PLSQLGatewayServlet.class);

	private ServletContext ctx;
    private Configuration config;
    private EntityConfig intconfig;
    private EntityConfig genconfig;
    
    /**
     * Default constructor. 
     */
    public PLSQLGatewayServlet()
    {
    }

	public void init(ServletConfig servletConfig)
	throws ServletException
	{
		ctx= servletConfig.getServletContext();
		config= Configuration.getInstance(ctx);
        intconfig= config.getInternal();
        genconfig= config.getGeneral();
	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	@SuppressWarnings("unchecked")
	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
	throws ServletException, IOException
	{
		long before= System.currentTimeMillis();
		
        request.setCharacterEncoding("UTF-8");
        
        String dadName;
        String dadPath;
        String pathInfo;
        
		if (genconfig.getBooleanParameter("embedded"))
		{
			dadPath= request.getContextPath()+request.getServletPath();
			
	        if (genconfig.getBooleanParameter("multiple-dad"))
			{
				String pi= request.getPathInfo();
				int fi= pi.indexOf('/',1);
				
				if (fi==-1)
				{
					dadPath+= pi;
					dadName= pi.substring(1);
					pathInfo= "/";
				}
				else
				{
					dadPath+= pi.substring(0,fi);
					dadName= pi.substring(1,fi);
					pathInfo= pi.substring(fi);
				}
			}
	        else
	        {
				dadName= "embedded";
				pathInfo= request.getPathInfo();
	        }
		}
		else
        if (genconfig.getBooleanParameter("multiple-dad"))
		{
			String pi= request.getPathInfo();
			int fi= pi.indexOf('/',1);
			
			if (fi==-1)
			{
				dadPath= request.getContextPath()+pi;
				dadName= pi.substring(1);
				pathInfo= "/";
			}
			else
			{
				dadPath= request.getContextPath()+pi.substring(0,fi);
				dadName= pi.substring(1,fi);
				pathInfo= pi.substring(fi);
			}
			
		}
		else
		{
			dadPath= request.getContextPath();
			pathInfo= request.getPathInfo();
			dadName= dadPath.substring(1);
		}
		
		if (logger.isDebugEnabled())
			logger.debug("dadPath: "+dadPath+" pathInfo: "+pathInfo+" dadName: "+dadName);
		        
		DataSource ds= getDADDataSource(dadName);
		
		if (ds==null)
		{
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		
		EntityConfig dadConfig= config.getDADConfig(dadName);
		
		if (pathInfo==null||pathInfo.equals("/"))
		{
			response.sendRedirect(dadPath+"/"+(dadConfig.getParameter("default-page")!=null ? dadConfig.getParameter("default-page") : "home"));
			return;
		}
		else
		if (pathInfo.equals("/_monitor"))
		{
			doMonitor(request,response,ds,dadConfig,dadName);
			return;
		} 
		else
		if (pathInfo.startsWith("/xdb"))
		{
			sendXDBFile(request,response,ds,dadConfig,dadName,pathInfo.substring(4));
			return;
		}
		
		OracleConnection conn= null;
	    PrintWriter pw= null;
		
		try 
		{
            conn= getConnection(ds,dadName);			
			if (dadConfig.getBooleanParameter("timed-statistics"))
				logger.fatal((System.currentTimeMillis()-before)+"ms: got connection");
			
			String[][] cgienv= getCgiEnv(request,dadName,pathInfo,dadPath,dadConfig,ctx);
			
			if (!authorize(request, response, conn, pathInfo, dadConfig, cgienv))
			{
				closeConnection(conn, dadConfig);
				return;
			}

	        RequestContext rc= new ServletRequestContext(request);
	        
	        Map parameterMap= request.getParameterMap();
	        Enumeration parameterNames= request.getParameterNames();

	        if (FileUpload.isMultipartContent(rc))
                try
                {
                	    final ArrayList names= new ArrayList(); 
                   	parameterMap= processMultipart(request, rc, conn, dadConfig, names);
                   	parameterNames= new Enumeration() 
                   	{
                        private Iterator i= names.iterator();
                        
						public boolean hasMoreElements() 
						{
							return i.hasNext();
						}

						public Object nextElement() 
						{
							return i.next();
						}
                   		
					};
                }
                catch(Exception ex)
                {
                    throw new ServletException(ex);
                }   
              
			DADProcedureCaller caller= new DADProcedureCaller(pathInfo,
					                                          parameterNames,
					                                          parameterMap,
					                                          request,
					                                          dadConfig,
					                                          cgienv,
					                                          intconfig,
					                                          genconfig);
			
			caller.call(conn);
			
			if (!caller.isAuthorized())
              logger.info("NOT AUTHORIZED by request-validation-function");
			
			OutputStream out= null;
			boolean body= false;

			while (caller.fetch(conn)>0)
			{
				String[] lines= caller.getLines();
				String lastLine= "";
				
				for (String line: lines)
				  if (body)
					pw.write(line);
				  else
				  {
					  if (line.equals("\n"))
					  {  
						body= true;
						response.setCharacterEncoding("UTF-8");
						out= response.getOutputStream();
						pw= new PrintWriter(out);
					  }
					  else
					  {
						if (line.endsWith("\n"))
						{
							lastLine+= line;
							String[] header= lastLine.split("\\: ");
							
							if (header.length==2)
							{
							    response.addHeader(header[0], header[1].substring(0, header[1].length()-1));
							    
							    if (header[0].equals("Location"))
						    	    response.setStatus(HttpServletResponse.SC_FOUND);
							    else
							    if (header[0].equals("Status"))
							    {
							    	int pos= header[1].indexOf(" ");
							    	String code= (pos==-1 ? header[1] : header[1].substring(0,pos));
				    	       	    response.setStatus(Integer.parseInt(code));
							    }
							    
							    lastLine= "";
							}
							
						}
						else
							lastLine+= line;
						
					  }
				  }
				
				if (pw!=null) pw.flush();
			}
			
			if (caller.isDocument())
			{
			   InputStream in= caller.getDocument(conn); 
			   byte[] buff= new byte[response.getBufferSize()];
			   int count= 0;
			   
			   while ((count=in.read(buff))>0)
                  out.write(buff,0,count);
			   
			   in.close();
			   out.flush();
			   out.close();
			}
			
		} 
		catch (SQLInjectionException sie)
		{
			logger.error(sie.getMessage()+" uri: "+request.getRequestURI());
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
		}
		catch (Exception e)
		{
			logger.error("error uri: "+request.getRequestURI(), e);
            
            if (dadConfig.getBooleanParameter("show-errors"))
            {
               if (pw==null)
                 pw= response.getWriter();

               e.printStackTrace(pw);
               pw.flush();
            }
            else 
			  throw new ServletException(e);
		}
		finally
		{
			if (conn!=null)
			  try { closeConnection(conn, dadConfig); } catch (SQLException e) {}
		}
		
		long after= System.currentTimeMillis();
        if (dadConfig.getBooleanParameter("timed-statistics"))
        	logger.fatal((after-before)+"ms: "+request.getRequestURL());
	}

	private OracleConnection getConnection(DataSource ds, String dadName)
	throws Exception
	{
		OracleConnection conn= null;
		try
		{
			conn= (OracleConnection)ds.getConnection();
		}
		catch (Exception ex)
		{
			try
			{
				ds= reloadDADDataSource(dadName);
				conn= (OracleConnection)ds.getConnection();
			}
			catch (Exception ex2)
			{
				logger.fatal("reinitializing DAD",ex2);
				throw ex; // throws the first exception that was the inital cause 
			}
		}
		
		conn.setAutoCommit(false);
		return conn;
	}

	private void sendXDBFile(HttpServletRequest request, HttpServletResponse response, DataSource ds, EntityConfig dadConfig, String dadName, String path) 
	{
		OracleConnection conn= null;
		OutputStream out= null;
		
		try 
		{
			
            conn= getConnection(ds,dadName);			
			
			PreparedStatement stmt= conn.prepareStatement("select XDBURIType(?).getBlob() content from dual");
			stmt.setString(1, path);
			OracleResultSet rs= (OracleResultSet)stmt.executeQuery();
			
			if (rs.next())
			{
				BLOB b= rs.getBLOB(1);
				
				if (b==null)
	    			   response.sendError(HttpServletResponse.SC_FORBIDDEN);
				else
				{
					byte[] buff= new byte[1024];
					int count= 0;
					InputStream in= b.getBinaryStream();
					out= response.getOutputStream();
					
					while ((count=in.read(buff))>0)
						out.write(buff, 0, count);
				
					out.flush();
				}
			}
			else
    			   response.sendError(HttpServletResponse.SC_FOUND);
			
			rs.close();
			stmt.close();
		}
		catch (Exception e)
		{
			logger.error("monitor service", e);
			try { response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); } catch (IOException ie) {}
		}
	    finally
	    {
	    	    if (conn!=null)
				try { closeConnection(conn, dadConfig); } catch (SQLException se) { logger.error("closing monitor connection", se); }

		    if (out!=null)
				try { out.close(); } catch (IOException e) {}
	    }
	}

	private void doMonitor(HttpServletRequest request, HttpServletResponse response, DataSource ds, EntityConfig dadConfig, String dadName)
	{
		OracleConnection conn= null;
		PrintWriter out= null;
		response.setContentType("text/xml");
		
		try 
		{
			out= response.getWriter();
			out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            conn= getConnection(ds,dadName);			
			
			PreparedStatement stmt= conn.prepareStatement("select * from dual");
			ResultSet rs= stmt.executeQuery();
			
			if (rs.next())
    			out.println("<ok/>");
			else
    			out.println("<ko/>");
			
			rs.close();
			stmt.close();
		}
		catch (Exception e)
		{
			logger.error("monitor service", e);
			if (out!=null) out.println("<ko><![CDATA["+e.getMessage()+"]]></ko>");
		}
	    finally
	    {
	    	if (conn!=null)
				try { closeConnection(conn, dadConfig); } catch (SQLException se) { logger.error("closing monitor connection", se); }

		    if (out!=null) 
		    	out.flush();
	    }
	
	}

	private boolean authorize(HttpServletRequest request,
			                  HttpServletResponse response,
			                  Connection conn,
			                  String pathInfo,
			                  EntityConfig dadConfig,
			                  String[][] cgienv) 
	throws Exception
	{
		if (!"PerPackageOwa".equals(dadConfig.getParameter("authentication-mode")))
		  return true;
		
		String auth= request.getHeader("Authorization");
		String user= null;
		String password= null;
		
		if (auth!=null)
		{
			String[] parts= auth.split(" ");
			
			if (parts.length==2&&parts[0].equalsIgnoreCase("Basic"))
			{
	            String userPass= new String(new BASE64Decoder().decodeBuffer(parts[1]));
	            
	            int sep= userPass.indexOf(':');
	            
	            if (sep!=-1)
	            {
	            	  user = userPass.substring(0, sep);
	                  password = userPass.substring(sep+1);
	            }	
			}
		}

		String pkg= null;
		
		String pparts[]= pathInfo.substring(1).split("\\.");
		
		if (pparts.length==1)
			return false;
		else
		if (pparts.length==2)
            pkg= pparts[0];			
		else
		if (pparts.length==3)
	        pkg= pparts[0]+"."+pparts[1];			
		
		OracleCallableStatement stmt= (OracleCallableStatement) conn.prepareCall(intconfig.getSQLstmt("AUTHORIZE").replaceFirst("#pkg#", pkg.replaceFirst("^!", "")));

        int retcode= 0;
        String realm= null;

        try
        {
            stmt.setInt(1, cgienv[0].length);
            setVcArr(stmt, 2, cgienv[0]);
            setVcArr(stmt, 3, cgienv[1]);

            stmt.setString(4, user);
            stmt.setString(5, password);
            
            if (dadConfig.getBooleanParameter("x-forwarded-for"))
                stmt.setString(6, request.getHeader("X-Forwarded-For"));
            else
                stmt.setString(6, request.getRemoteAddr());
            
            stmt.setString(7, request.getRemoteHost());
            
            stmt.registerOutParameter(8, OracleTypes.NUMBER);
            stmt.registerOutParameter(9, OracleTypes.VARCHAR);
            
            stmt.execute();		
            
            retcode = stmt.getInt(8);
            realm = stmt.getString(9);
        }
        finally
        {
            stmt.close();
        } 

        if (retcode==0)
        {
	        response.setHeader("WWW-Authenticate", "Basic realm=\""+realm+"\"");
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
     		return false;
        }
        else
        	return true;
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
	throws ServletException, IOException
	{
		doGet(request, response);
	}
	
	/**
	 * @see HttpServlet#doDelete(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doDelete(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException 
    {
		doGet(request, response);
	}
	
	/**
	 * @see HttpServlet#doTrace(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPut(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException 
    {
		doGet(request, response);
	}
	
	/**
	 * @see HttpServlet#doTrace(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doTrace(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException 
    {
		doGet(request, response);
	}
	
	/**
	 * @see HttpServlet#doHead(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doHead(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException 
    {
		doGet(request, response);
	}
	
	/**
	 * @see HttpServlet#doOptions(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doOptions(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException 
    {
		doGet(request, response);
	}
	
	private void setVcArr(OracleCallableStatement stmt, int parameterIndex, String[] arrayData)
	throws Exception
	{
		  stmt.setPlsqlIndexTable(parameterIndex, arrayData, arrayData.length, arrayData.length, OracleTypes.VARCHAR, 32767);
	}

	@SuppressWarnings({ "unchecked" })
	private void addParamValue(Map uploadParams, String name, String value)
	{
        Object par= uploadParams.get(name);
		
        if (par == null)
        {
        	uploadParams.put(name, value);
        }
        else
        if (par instanceof String[])
        {
        	String[] olda= (String[])par;
        	String[] newa= new String[olda.length+1];
        	int i= 0;
        	for (i = 0; i < olda.length; i++) 
        	   newa[i]= olda[i];
        	
        	newa[i]= value;
        	
        	uploadParams.put(name, newa);
        }
        else
        {
        	uploadParams.put(name, new String[]{(String)par,value});
        }
	}

	@SuppressWarnings("unchecked")
	private Map processMultipart(HttpServletRequest request, RequestContext rc, OracleConnection conn, EntityConfig dadConfig, ArrayList names) 
	throws FileUploadException
	{
        ServletFileUpload sfu= new ServletFileUpload(new OracleFileItemFactory(conn,intconfig,dadConfig));
        List items= sfu.parseRequest(rc);
        HashMap uploadParams= new HashMap(request.getParameterMap());
        Iterator i= items.iterator();
        Iterator n= uploadParams.keySet().iterator();

        while (n.hasNext())
        {
           String name= (String)n.next();
           if (!names.contains(name)) names.add(name);
        }

        while (i.hasNext())
        {
           OracleFileItem fi= (OracleFileItem)i.next();
           
           if (!names.contains(fi.getFieldName())) names.add(fi.getFieldName());
           
           if (fi.isFormField())
        	   		addParamValue(uploadParams,fi.getFieldName(), fi.getString());
           else
        	   		addParamValue(uploadParams,fi.getFieldName(), fi.getDocumentId());
        }
        
        return uploadParams;
	}

	private DataSource getDADDataSource(String dadName)
	{
		return (DataSource) ctx.getAttribute(DADContextListener.DAD_DATA_SOURCE+"|"+dadName);
	}
	
	private DataSource reloadDADDataSource(String dadName)
	throws Exception
	{
		DADContextListener.initializeDAD(dadName,config,ctx);
		return (DataSource) ctx.getAttribute(DADContextListener.DAD_DATA_SOURCE+"|"+dadName);
	}

	private static final String[][] getCgiEnv(HttpServletRequest request, String dadName, String pathInfo, String dadPath, EntityConfig dadConfig, ServletContext ctx)
	throws IOException
	{
		HashMap<String,String> m= new HashMap<String,String>(32);
		int len= 32;
		
		m.put("PLSQL_GATEWAY", "com.google.code.plsqlgateway");
		m.put("GATEWAY_VERSION", String.valueOf(serialVersionUID));
		m.put("SERVER_SOFTWARE", ctx.getServerInfo());
		m.put("GATEWAY_INTERFACE", "CGI/1.1");
		m.put("SERVER_PORT", String.valueOf(request.getServerPort()));
		m.put("SERVER_NAME", request.getServerName());
		m.put("REQUEST_METHOD", request.getMethod());
		m.put("PATH_INFO", pathInfo);
		m.put("SCRIPT_NAME", dadPath);
		
		if (dadConfig.getBooleanParameter("x-forwarded-for"))
			m.put("REMOTE_ADDR", request.getHeader("X-Forwarded-For"));
		else
			m.put("REMOTE_ADDR", request.getRemoteAddr());
		
		m.put("REMOTE_HOST", request.getRemoteHost());  	
		m.put("SERVER_PROTOCOL", request.getProtocol());
		m.put("REQUEST_PROTOCOL", request.getScheme());
		m.put("REMOTE_USER", getRemoteUser(request));
		m.put("HTTP_CONTENT_LENGTH", String.valueOf(request.getContentLength()));
		m.put("HTTP_CONTENT_TYPE", request.getContentType());
		m.put("HTTP_USER_AGENT", request.getHeader("User-Agent"));
		m.put("HTTP_HOST", request.getServerName()+":"+request.getServerPort());
		m.put("HTTP_ACCEPT", request.getHeader("Accept"));
		m.put("HTTP_ACCEPT_ENCODING", request.getHeader("Accept-Encoding"));
		m.put("HTTP_ACCEPT_LANGUAGE", request.getHeader("Accept-Language"));
		m.put("HTTP_ACCEPT_CHARSET", request.getHeader("Accept-Charset"));
		m.put("HTTP_COOKIE", request.getHeader("Cookie"));
		m.put("HTTP_REFERER", request.getHeader("Referer"));			
		m.put("WEB_AUTHENT_PREFIX", "");
		m.put("DAD_NAME", dadName);
		m.put("DOC_ACCESS_PATH", dadConfig.getParameter("document-path"));
		m.put("DOCUMENT_TABLE", dadConfig.getParameter("document-table-name"));
		m.put("PATH_ALIAS", dadConfig.getParameter("path-alias"));
		m.put("REQUEST_CHARSET", "AL32UTF8");
		m.put("REQUEST_IANA_CHARSET", "UTF-8");
		m.put("SCRIPT_PREFIX", "");
		
		if (request.getHeader("SOAPAction")!=null)
		{
			  m.put("HTTP_SOAPACTION", request.getHeader("SOAPAction"));
			  String[] soapBody= getBody(request);
			  
			  len+=2;

			  if (soapBody.length==1)
			      m.put("SOAP_BODY", soapBody[0]);
			  else
			  {
			      m.put("SOAP_BODY_LENGTH", soapBody.length+"");
			      
			      for (int i = 0; i < soapBody.length; i++) 
	  			      m.put("SOAP_BODY_"+(i+1), soapBody[i]);

	  			  len+= soapBody.length;  			      
			  }  				  
		}
		else
		if (request.getContentType()!=null
            &&
            !(request.getContentType().startsWith("application/x-www-form-urlencoded")
            ||request.getContentType().startsWith("multipart/form-data")))
		{
			String[] body= getBody(request);
			
			len++;
			
			if (body.length==1)
				m.put("REQUEST_BODY", body[0]);
			else
  		    {
		      m.put("REQUEST_BODY_LENGTH", body.length+"");
		      
		      for (int i = 0; i < body.length; i++) 
  			      m.put("REQUEST_BODY_"+(i+1), body[i]);

  			  len+= body.length;  			      
		    }  				  
		}		
		
		String[][] retval= new String[2][len];
		
		Iterator<Entry<String,String>> i= m.entrySet().iterator();
		int idx= 0;
		
		while (i.hasNext())
		{
			Entry<String,String> e= i.next();
			retval[0][idx]= e.getKey();
			retval[1][idx++]= e.getValue();
		}
		
        return retval;
	}	
  
    private static final String getRemoteUser(HttpServletRequest request)
    {
       String user= (request.getUserPrincipal()==null ? request.getRemoteUser() : request.getUserPrincipal().getName()); 
       if (user==null)
         user= (String)request.getAttribute("REMOTE_USER");

       return user;
    }

	private static final String[] getBody(HttpServletRequest request) 
	throws IOException
	{
		ByteArrayOutputStream baos= new ByteArrayOutputStream();
		InputStream in= request.getInputStream();
		
		byte[] buff= new byte[1024];
        int count= 0;		
        
	    while ((count=in.read(buff))>0)
	    	baos.write(buff, 0, count);
	    
	    baos.flush();
	    in.close();
	    
	    String soapBody= new String(baos.toByteArray(),request.getCharacterEncoding());
	    
	    baos.close();
	    
	    String[] retval= null;
	    
	    int max_header_length= 30000; // leave 2000 bytes of overflow for multibyte chars
	    
	    if (soapBody.length()<max_header_length)
	    	retval= new String[]{soapBody};
	    else
	    {	
	        retval= new String[(int) Math.ceil((double)soapBody.length()/(double)max_header_length)];
	        int offset= 0;
	        
	        for (int i=0;i<retval.length-1;i++)
	        {
	        	retval[i]= soapBody.substring(offset, offset+max_header_length);
	        	offset+=max_header_length;
	        }
	        
	        retval[retval.length-1]= soapBody.substring(offset);
	    }
	    
	    return retval;
	}
	
    private void closeConnection(Connection conn, EntityConfig dadConfig)
    throws SQLException
    {
    	try
    	{
	    	 if (dadConfig.getBooleanParameter("reset-packages"))
	    		 resetPackages(conn);
    	}
    	catch (SQLException ex)
    	{
    		logger.error("resetting package states",ex);
    		throw ex;
    	}
    	finally
    	{
           conn.close();
    	}
    }

	private void resetPackages(Connection conn)
	throws SQLException
	{
        CallableStatement stmt= conn.prepareCall(intconfig.getSQLstmt("RESET_PACKAGES"));
        stmt.execute();
        stmt.close();
	}
}
