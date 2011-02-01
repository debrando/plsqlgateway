package com.google.code.plsqlgateway.servlet.upload;

import com.google.code.eforceconfig.Config;
import com.google.code.eforceconfig.EntityConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

import java.io.UnsupportedEncodingException;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import oracle.jdbc.OracleTypes;

import oracle.jdbc.internal.OracleResultSet;

import oracle.sql.BLOB;

import org.apache.commons.fileupload.FileItem;
import org.apache.log4j.Logger;

public class OracleFileItem implements FileItem {
   
    private static Logger logger= Logger.getLogger(OracleFileItem.class);
    private String fieldName;
    private String contentType;
    private String documentId="";
    private boolean isFormField;
    private String fileName;
    private BLOB blob;
    private ByteArrayOutputStream bos;
    private EntityConfig intconfig;
    private EntityConfig dadConfig;
    private OracleConnection conn;
    private OracleCallableStatement stmt;
    
    public OracleFileItem(OracleConnection conn, EntityConfig intconfig, EntityConfig dadConfig, String fieldName, String contentType, boolean isFormField, String fileName) 
    {
 	   logger.debug("fieldName: "+fieldName+" contentType: "+contentType+" isFormField: "+isFormField+" fileName: "+fileName+" instance: "+this);

 	   this.isFormField= isFormField;
 	   this.fieldName= fieldName;
 	   this.contentType= contentType;
 	   this.fileName= fileName;
 	   this.conn= conn;
       this.intconfig= intconfig;
	   this.dadConfig= dadConfig;
 	   
 	   if (!isFormField)
           {
        	   logger.debug("uploaded file found"+conn);
               
        	   if (!fileName.equals(""))
        		 if (!"id".equals(dadConfig.getParameter("document-upload-mode")))   
		               try
		               {
		                       documentId= "F"+Math.random()+"/"+fileName;
		                       stmt= (OracleCallableStatement) conn.prepareCall(intconfig.getSQLstmt("OWA_UPLOAD").replaceFirst("#table#", dadConfig.getParameter("document-table-name")));
		                       stmt.setString(1, documentId);
		                       stmt.setString(2, contentType);
		                       stmt.registerOutParameter(3, OracleTypes.BLOB);
		                       stmt.execute();
		                       
		                       blob= stmt.getBLOB(3);
		                       blob.open(BLOB.MODE_READWRITE);
		                       logger.debug("blob: "+blob);
		               }
		               catch (Exception ex)
		               {
		            	  logger.error("blob handling",ex);
		                  throw new RuntimeException(ex);
		               }
		         else
		               try
		               {
		                       stmt= (OracleCallableStatement) conn.prepareCall(intconfig.getSQLstmt("OWA_UPLOAD_ID")
		                    		                        .replaceFirst("#table#", dadConfig.getParameter("document-table-name"))
		                    		                        .replaceFirst("#sequence#", dadConfig.getParameter("document-upload-seq")));
		                       stmt.setString(1, fileName);
		                       stmt.setString(2, contentType);
		                       stmt.registerOutParameter(3, OracleTypes.BLOB);
		                       stmt.registerOutParameter(4, OracleTypes.VARCHAR);
		                       stmt.execute();
		                       
		                       blob= stmt.getBLOB(3);
		                       documentId= stmt.getString(4);
		                       
		                       blob.open(BLOB.MODE_READWRITE);
		                       logger.debug("blob: "+blob);
		               }
		               catch (Exception ex)
		               {
		            	  logger.error("blob handling",ex);
		                  throw new RuntimeException(ex);
		               }
		            	   
           }
           else
           {
               bos= new ByteArrayOutputStream();           
           }
    }

    public InputStream getInputStream() {
        if (!isFormField)
            try {
                return blob.getBinaryStream();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        else
                throw new RuntimeException("not implemented");
        
    }

    public String getContentType() {
        return contentType;
    }

    public String getName() {
        return fieldName;
    }

    public String getDocumentId() {
    	logger.debug("getDocumentId(): "+this+" documentId: "+documentId);
    	
    	if (!fileName.equals(""))
    	{
	        try
	        {
	        	blob.close();
	            stmt.close();  

         		if (!"id".equals(dadConfig.getParameter("document-upload-mode")))   
         		{
		            OraclePreparedStatement stmt= (OraclePreparedStatement) conn.prepareStatement(intconfig.getSQLstmt("OWA_UPLOAD_SIZE").replaceFirst("#table#", dadConfig.getParameter("document-table-name")));
		            stmt.setString(1,documentId);
		            stmt.execute();
         		}
         		else
         		{
		            OraclePreparedStatement stmt= (OraclePreparedStatement) conn.prepareStatement(intconfig.getSQLstmt("OWA_UPLOAD_ID_SIZE").replaceFirst("#table#", dadConfig.getParameter("document-table-name")));
		            stmt.setString(1,documentId);
		            stmt.execute();
         		}
         			
	        }
	        catch (Exception ex)
	        {
	            throw new RuntimeException(ex);
	        }
	        
    	}   
        
        return documentId;
    }

    public boolean isInMemory() {
        return isFormField;
    }

    public long getSize() {
        if (!isFormField)
          return blob.getLength();
        else
          return bos.toByteArray().length;
    }

    public byte[] get() {
        if (!isFormField)
          return blob.getBytes();
        else
          return bos.toByteArray();
    }

    public String getString(String charset) {
    	String value;
        try {
                if (!isFormField)
                    value= new String(blob.getBytes(),charset);
                else
                	value= new String(bos.toByteArray(), charset);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        logger.debug(fieldName+": "+value);
        return value;
    }

    public String getString() {
        if (isFormField)
          return new String(bos.toByteArray());
        else
          throw new RuntimeException("not implemented");
    }

    public void write(File file) {
        throw new RuntimeException("not implemented");
    }

    public void delete() {
        throw new RuntimeException("not implemented");
      /* TODO: delete blob ??*/
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName= fieldName;
    }

    public boolean isFormField() {
        return isFormField;
    }

    public void setFormField(boolean isFormField) {
        this.isFormField= isFormField;
    }

    public OutputStream getOutputStream() {
    	logger.debug("getOutputStream() blob: "+blob);
    	
    	if (blob != null)
            try {
                return blob.setBinaryStream(0L);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        else
        {
            return bos;
        }
    }
}
