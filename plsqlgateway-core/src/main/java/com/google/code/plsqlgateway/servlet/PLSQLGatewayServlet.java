package com.google.code.plsqlgateway.servlet;

import com.google.code.eforceconfig.EntityConfig;
import com.google.code.plsqlgateway.config.Configuration;
import com.google.code.plsqlgateway.dad.DADProcedureCaller;
import com.google.code.plsqlgateway.servlet.upload.OracleFileItem;
import com.google.code.plsqlgateway.servlet.upload.OracleFileItemFactory;
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

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.Map.Entry;
import java.net.SocketException;

/**
 * Servlet implementation class PLSQLGatewayServlet
 */
public class PLSQLGatewayServlet extends HttpServlet 
{
	private static final long serialVersionUID = 1L;
	private static final Logger logger= Logger.getLogger(PLSQLGatewayServlet.class);
    private static final String DEV_USER= System.getProperty("com.google.code.plsqlgateway.REMOTE_USER");

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

		logger.debug(String.format("doGet: dadPath '%s', pathInfo '%s', dadName '%s'", dadPath, pathInfo, dadName));
		        
		DataSource ds= getDADDataSource(dadName);
		
		if (ds==null)
		{
            logger.info("doGet: return not found");
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		
		EntityConfig dadConfig= config.getDADConfig(dadName);
		
		if (pathInfo==null||pathInfo.equals("/"))
		{
            String where = String.format("%s/%s", dadPath, dadConfig.getParameter("default-page") != null ? dadConfig.getParameter("default-page") : "home");
            logger.info(String.format("doGet: return redirect to %s", where));
			response.sendRedirect(where);
			return;
		}
		else
		if (pathInfo.equals("/_monitor"))
		{
            logger.info("doGet: return monitor");
			doMonitor(request,response,ds,dadConfig,dadName);
			return;
		} 
		else
		if (pathInfo.startsWith("/xdb"))
		{
            logger.info(String.format("doGet: return XDB file %s", pathInfo.substring(4)));
			sendXDBFile(request,response,ds,dadConfig,dadName,pathInfo.substring(4));
			return;
		}
		
		OracleConnection conn= null;
        OutputStream out= null;
	    PrintWriter pw= null;
		
		try 
		{
            conn= getConnection(ds, dadName, dadConfig, request, response);			

            if (conn==null) return; // Basic auth

			String[][] cgienv= getCgiEnv(request,dadName,pathInfo,dadPath,dadConfig,ctx);
			
			if (!authorize(request, response, conn, pathInfo, dadConfig, cgienv))
			{
                logger.info("doGet: return unauthorized");
				closeConnection(conn, dadConfig, "doGet");
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
                logger.info("doGet: NOT AUTHORIZED by request-validation-function");

			boolean body= false;

            while (caller.fetch(conn) > 0) {
                String[] lines = caller.getLines();
                String lastLine = "";

                for (String line : lines)
                    if (body)
                        pw.write(line);
                    else {
                        if (line.equals("\n")) {
                            body = true;
                            response.setCharacterEncoding("UTF-8");
                            out = response.getOutputStream();
                            pw = new PrintWriter(out);
                        } else {
                            if (line.endsWith("\n")) {
                                lastLine += line;
                                String[] header = lastLine.split("\\: ");

                                if (header.length == 2) {
                                    response.addHeader(header[0], header[1].substring(0, header[1].length() - 1));

                                    if (header[0].equals("Location")) {
                                        response.setStatus(HttpServletResponse.SC_FOUND);
                                    } else if (header[0].equals("Status")) {
                                        int pos = header[1].indexOf(" ");
                                        String code = (pos == -1 ? header[1] : header[1].substring(0, pos));
                                        response.setStatus(Integer.parseInt(code));
                                    }
                                    lastLine = "";
                                }

                            } else {
                                lastLine += line;
                            }
                        }
                    }

                if (pw != null && !response.isCommitted())
                    pw.flush();
            }

            if (out != null && caller.isDocument()) {
                InputStream in = caller.getDocument(conn);
                byte[] buff = new byte[response.getBufferSize()];
                int count;

                while ((count = in.read(buff)) > 0)
                    out.write(buff, 0, count);

                in.close();
            }
		} 
		catch (SQLInjectionException sie)
		{
			logger.error(String.format("doGet: SQLInjection on %s: %s", request.getRequestURI(), sie.getMessage()));
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
		}
        catch (java.io.IOException e) {
            if (e.getCause() instanceof SocketException) {
                logger.info("doGet: error uri " + e.getMessage());
            } else {
                logger.error("doGet: error uri " + request.getRequestURI(), e);
            }
            throw new ServletException(e);
        }
		catch (Exception e)
		{
			logger.error("doGet: error uri "+request.getRequestURI(), e);
            
            if (dadConfig.getBooleanParameter("show-errors") && !response.isCommitted())
            {
               if (pw==null) {
                   pw = response.getWriter();
               }
               e.printStackTrace(pw);
            }
            else {
                throw new ServletException(e);
            }
		}
		finally {
            if (pw != null) {
                pw.close();
            } else if (out != null) {
                out.close();
            }
            closeConnection(conn, dadConfig, "doGet");
		}

        logger.info(String.format("doGet: %s took %dms", request.getRequestURL(), System.currentTimeMillis()-before));
	}

	private OracleConnection getConnection(DataSource ds, 
                                           String dadName,
                                           EntityConfig dadConfig,
                                           HttpServletRequest request,
                                           HttpServletResponse response)
	throws Exception
	{
        long before = System.currentTimeMillis();
        String user= null;
        String password= null;
        OracleConnection conn;

        if ("Basic".equals(dadConfig.getParameter("authentication-mode")))
        {
            String auth= request.getHeader("Authorization");
            
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
            else
            {
                response.setHeader("WWW-Authenticate", "Basic realm=\""+dadName+"\"");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return null;
            }

            try
            {
                conn= (OracleConnection)ds.getConnection(user,password);
            }
            catch (Exception ex)
            {
                try
                {
                    ds= reloadDADDataSource(dadName);
                    conn= (OracleConnection)ds.getConnection(user,password);
                }
                catch (Exception ex2)
                {
                    logger.fatal("getConnection: reinitializing DAD",ex2);
                    throw ex; // throws the first exception that was the inital cause 
                }
            }
        }
        else
        {
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
                    logger.fatal("getConnection: reinitializing DAD",ex2);
                    throw ex; // throws the first exception that was the inital cause 
                }
            }
        }

		conn.setAutoCommit(false);
        logger.debug(String.format("getConnection: provided a db connection for %s in %dms", dadName, System.currentTimeMillis()-before));
		return conn;
	}

	private void sendXDBFile(HttpServletRequest request, HttpServletResponse response, DataSource ds, EntityConfig dadConfig, String dadName, String path) 
	{
		OracleConnection conn= null;
		OutputStream out= null;
		
		try 
		{
			
            conn= getConnection(ds,dadName,dadConfig,request,response);

			PreparedStatement stmt= conn.prepareStatement("select XDBURIType(?).getBlob() content from dual");
			stmt.setString(1, path);
            try {
                OracleResultSet rs = (OracleResultSet) stmt.executeQuery();

                try {
                    if (rs.next()) {
                        BLOB b = rs.getBLOB(1);

                        if (b == null)
                            response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        else {
                            byte[] buff = new byte[1024];
                            int count;
                            InputStream in = b.getBinaryStream();
                            out = response.getOutputStream();

                            while ((count = in.read(buff)) > 0)
                                out.write(buff, 0, count);

                            out.flush();
                        }
                    } else {
                        response.sendError(HttpServletResponse.SC_FOUND);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                stmt.close();
            }
		}
		catch (Exception e)
		{
			logger.error("sendXDBFile", e);
			try { response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); }
            catch (IOException ie) {
                logger.error("sendXDBFile", ie);
            }
		}
	    finally {
            closeConnection(conn, dadConfig, "sendXDBFile");
		    if (out!=null)
				try { out.close(); }
                catch (IOException e) {
                    logger.error("sendXDBFile", e);
                }
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
            conn= getConnection(ds,dadName,dadConfig,request,response);			
			
			PreparedStatement stmt= conn.prepareStatement("select * from dual");
            try {
                ResultSet rs= stmt.executeQuery();
                try {
                    if (rs.next())
                        out.println("<ok/>");
                    else
                        out.println("<ko/>");
                } finally {
                    rs.close();
                }
            } finally {
                stmt.close();
            }
		}
		catch (Exception e)
		{
			logger.error("doMonitor", e);
			if (out!=null) out.println("<ko><![CDATA["+e.getMessage()+"]]></ko>");
		}
        finally {
            closeConnection(conn, dadConfig, "doMonitor");
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

        String query;
        try {
            query = intconfig.getSQLstmt("AUTHORIZE").replaceFirst("#pkg#", pkg.replaceFirst("^!", ""));
        } catch (NullPointerException ne) {
            logger.error("authorize: wrong statement syntax");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return false;
        }

        int retcode= 0;
        String realm= null;
        OracleCallableStatement stmt = (OracleCallableStatement) conn.prepareCall(query);
        try {
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
        } finally {
            stmt.close();
        }

        if (retcode==0) {
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
            int i = 0;
        	for (; i < olda.length; i++)
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

        for (Object o : uploadParams.keySet()) {
            String name = (String) o;
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

	private static String[][] getCgiEnv(HttpServletRequest request, String dadName, String pathInfo, String dadPath, EntityConfig dadConfig, ServletContext ctx)
	throws IOException
	{
		HashMap<String,String> m= new HashMap<String,String>(32);
		int len= 33;
		
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
		m.put("QUERY_STRING", request.getQueryString());
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
  
    private static String getRemoteUser(HttpServletRequest request)
    {
       String user= DEV_USER;
 
       if (user==null)
       {
         user= (request.getUserPrincipal()==null ? request.getRemoteUser() : request.getUserPrincipal().getName()); 

         if (user==null)
            user= (String)request.getAttribute("REMOTE_USER");
       }

       return user;
    }

	private static String[] getBody(HttpServletRequest request)
	throws IOException
	{
		ByteArrayOutputStream baos= new ByteArrayOutputStream();
		InputStream in= request.getInputStream();
		
		byte[] buff= new byte[1024];
        int count;
        
	    while ((count=in.read(buff))>0)
	    	baos.write(buff, 0, count);
	    
	    baos.flush();
	    in.close();
	    
	    String soapBody= new String(baos.toByteArray(),request.getCharacterEncoding());
	    
	    baos.close();
	    
	    String[] retval;
	    
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
	
    private void closeConnection(Connection conn, EntityConfig dadConfig, String description)
    {
        if (conn == null) {
            logger.debug(String.format("closeConnection: Connection already null for %s", description));
            return;
        }
    	try {
	    	 if (dadConfig.getBooleanParameter("reset-packages")) {
                 resetPackages(conn);
                 logger.debug(String.format("closeConnection: Resetted db packages for %s", description));
             }
    	}
    	catch (SQLException ex) {
            logger.error(String.format("closeConnection: Cannot reset db packages for %s", description), ex);
    	}
    	finally {
            try {
                conn.close();
                logger.debug(String.format("closeConnection: Closed a db connection for %s", description));
            }
            catch (Exception e) {
                logger.error(String.format("closeConnection: Cannot close a db connection for %s", description));
            }
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
