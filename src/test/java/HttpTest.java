import api.Application;
import com.ibm.watson.developer_cloud.service.security.IamOptions;
import com.ibm.watson.developer_cloud.visual_recognition.v3.VisualRecognition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class HttpTest {
    private Application app;
    private VisualRecognition service;
    private String rootPath;
    @BeforeEach
    public void setUp() throws IOException {
        app = new Application();
        //Set up properties and load secrets
        rootPath = Thread.currentThread().getContextClassLoader().getResource("").getPath().replace("%20", " ");
        String secretsPath = rootPath + "secrets.properties";
        Properties secrets = new Properties();
        secrets.load(new FileInputStream(secretsPath));

        //Set up connection to IBM Watson Image Recognition
        service = new VisualRecognition("2018-03-19");
        service.setEndPoint("https://gateway.watsonplatform.net/visual-recognition/api");
        IamOptions options = new IamOptions.Builder()
                .apiKey(secrets.getProperty("api-key"))
                .build();
        service.setIamCredentials(options);

        File uploadDir = new File("upload");
        uploadDir.mkdir(); // create the upload directory if it doesn't exist

    }
    @Test
    public void testBuildings() {
        Application app = new Application();
        app.getBuildingsNearLocation(40.680692, -73.988398, 0, 3)
                .forEach(building -> System.out.println(building));
    }

    @Test
    public void testChoppedImage() throws IOException {
        InputStream in = new FileInputStream(rootPath+ "large_test.png");
        System.out.println(app.classifyAndRetrieveData(service, in, 40.680692, -73.988398, 0));
    }

    @Test
    public void testSingleImage() throws FileNotFoundException {
        InputStream whole = new FileInputStream(rootPath+ "test.png");
        InputStream upper = new FileInputStream(rootPath+ "test_upper.png");
        InputStream lower = new FileInputStream(rootPath+ "test_lower.png");
        System.out.println(app.containsBuilding(service, whole));
        System.out.println(app.containsBuilding(service, upper));
        System.out.println(app.containsBuilding(service, lower));
    }

    @Test
    public void testBlobExtraction() {
        List<List<Boolean>> raw = new ArrayList<>();
        raw.add(new ArrayList<>(Arrays.asList(false, false, true, false)));
        raw.add(new ArrayList<>(Arrays.asList(false, false, true, false)));
        raw.add(new ArrayList<>(Arrays.asList(false, false, true, false)));
        raw.add(new ArrayList<>(Arrays.asList(false, false, true, false)));
        List<List<Integer>> processed = app.extractBlobs(raw);
        for (List<Integer> row : processed) {
            for (int i : row) {
                System.out.print(i + " ");
            }
            System.out.println();
        }
    }

}
