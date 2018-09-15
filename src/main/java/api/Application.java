package api;

import com.ibm.watson.developer_cloud.service.security.IamOptions;
import com.ibm.watson.developer_cloud.visual_recognition.v3.VisualRecognition;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.ClassifiedImages;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.ClassifyOptions;
import spark.Response;
import spark.Route;

import javax.servlet.MultipartConfigElement;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Properties;

import static spark.Spark.get;
import static spark.Spark.post;

public class Application {

    public static void main(String[] args) throws IOException {
        //Set up properties and load secrets
        String rootPath = Thread.currentThread().getContextClassLoader().getResource("").getPath().replace("%20", " ");
        String secretsPath = rootPath + "secrets.properties";
        Properties secrets = new Properties();
        secrets.load(new FileInputStream(secretsPath));

        //Set up connection to IBM Watson Image Recognition
        VisualRecognition service = new VisualRecognition("2018-03-19");
        service.setEndPoint("https://gateway.watsonplatform.net/visual-recognition/api");
        IamOptions options = new IamOptions.Builder()
                .apiKey(secrets.getProperty("api-key"))
                .build();
        service.setIamCredentials(options);

        File uploadDir = new File("upload");
        uploadDir.mkdir(); // create the upload directory if it doesn't exist


        //Begin spark
        post("/image", ((request, response) -> {
            request.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("/temp"));
            try (InputStream input = request.raw().getPart("file").getInputStream()) {
                return classifyImage(service, input);
            }catch (IOException e) {
                return e.getMessage();
            }


        }));
    }

    public static String classifyImage(VisualRecognition service, InputStream input) {
        System.out.println("Classify called");
            ClassifyOptions classifyOptions = new ClassifyOptions.Builder()
                    .imagesFile(input)
                    .imagesFilename("test.jpg")
                    .threshold((float) 0.6)
                    .classifierIds(Arrays.asList("default"))
                    .build();
            ClassifiedImages result = service.classify(classifyOptions).execute();
            System.out.println(result);
            return result.toString();
    }
}
