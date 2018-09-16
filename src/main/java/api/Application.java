package api;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.watson.developer_cloud.service.security.IamOptions;
import com.ibm.watson.developer_cloud.visual_recognition.v3.VisualRecognition;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.*;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import model.Building;
import model.Coordinates;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.servlet.MultipartConfigElement;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import static spark.Spark.post;

public class Application {
    public static final double FOOT_DEGREE = 0.000002742701671;
    public static final double LOCATION_RANGE = 1000;

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
            double lat = Double.parseDouble(request.queryParams("lat"));
            double lon = Double.parseDouble(request.queryParams("lon"));
            double bearing = Double.parseDouble(request.queryParams("bearing"));
            try (InputStream input = request.raw().getPart("file").getInputStream()) {
                return classifyAndRetrieveData(service, input, lat, lon, bearing);
            } catch (IOException e) {
                return e.getMessage();
            }


        }));

    }

    public static String classifyAndRetrieveData(VisualRecognition service, InputStream input, double lat, double lon, double bearing) {
        List<Coordinates> boxes = classifyImage(service, splitImage(input));
        //Todo: Return image coords, sort buildings
        Set<Building> buildings = getBuildingsNearLocation(lat, lon, bearing);
        ObjectMapper mapper = new ObjectMapper();
        try {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("Image", mapper.valueToTree(image));
            root.put("Buildings", mapper.valueToTree(buildings));
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return e.getMessage();
        }

    }

    public static List<List<InputStream>> splitImage(InputStream input) {
        final BufferedImage source;
        List<List<InputStream>> chopped = new ArrayList<>();
        try {
            source = ImageIO.read(input);
            for (int y = 0; y < source.getHeight(); y += 32) {
                chopped.add(new ArrayList<>());
                for (int x = 0; x < source.getWidth(); x += 32) {
                    BufferedImage subImage = source.getSubimage(x, y, 32, 32);
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    ImageIO.write(subImage, "jpg", os);
                    InputStream is = new ByteArrayInputStream(os.toByteArray());
                    chopped.get(y).add(is);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return chopped;
    }

    public static List<Coordinates> classifyImage(VisualRecognition service, List<List<InputStream>> chopped) {
        List<List<Boolean>> foundBuilding = chopped.parallelStream()
                .map(row ->
                        row.parallelStream()
                                .map(image -> containsBuilding(service, image))
                                .collect(Collectors.toList())
                )
                .collect(Collectors.toList());
        List<Coordinates> boxes = new ArrayList<>();
        int startx = 0;
        int starty = 0;
        int endx = 0;
        int endy = 0;
        for(int y=0; y < foundBuilding.size(); y++) {
            for(int x = 0; x< foundBuilding.get(y).size(); x++) {
                if(foundBuilding.get(y).get(x)) {
                    endx = Math.max(x, endx);
                    endy = Math.max(y, endy);
                } else {

                }
            }
        }
    }

    public static boolean containsBuilding(VisualRecognition service, InputStream image){
        List<ClassResult> classes = classifyImage(service, image).getClassifiers().get(0).getClasses();
        for(ClassResult result: classes) {
            if (result.getClassName().equals("Building")){
                return true;
            }
        }
        return false;
    }

    public static ClassifiedImage classifyImage(VisualRecognition service, InputStream input) {
        ClassifyOptions classifyOptions = new ClassifyOptions.Builder()
                .imagesFile(input)
                .imagesFilename("test.jpg")
                .threshold((float) 0.6)
                .classifierIds(Arrays.asList("default"))
                .build();
        ClassifiedImages result = service.classify(classifyOptions).execute();
        return result.getImages().get(0);
    }

    public static Set<Building> getBuildingsNearLocation(double lat, double lon, double bearing) {
        Set<Building> buildings = new HashSet<>();
        double lat1, lon1;
        if (bearing > 315 || bearing < 45) {
            lat1 = lat + FOOT_DEGREE * LOCATION_RANGE;
            lon1 = lon + FOOT_DEGREE * LOCATION_RANGE / 2;
            lon -= FOOT_DEGREE * LOCATION_RANGE / 2;
        } else if (bearing < 135) {
            lon1 = lon + FOOT_DEGREE * LOCATION_RANGE;
            lat1 = lat + FOOT_DEGREE * LOCATION_RANGE / 2;
            lat -= FOOT_DEGREE * LOCATION_RANGE / 2;
        } else if (bearing < 225) {
            lat1 = lat - FOOT_DEGREE * LOCATION_RANGE;
            lon1 = lon + FOOT_DEGREE * LOCATION_RANGE / 2;
            lon -= FOOT_DEGREE * LOCATION_RANGE / 2;
        } else {
            lon1 = lon - FOOT_DEGREE * LOCATION_RANGE;
            lat1 = lat + FOOT_DEGREE * LOCATION_RANGE / 2;
            lat -= FOOT_DEGREE * LOCATION_RANGE / 2;
        }
//        System.out.println(String.format("%f, %f, %f, %f", lat, lat1, lon, lon1));

        try {
            HttpResponse<JsonNode> jsonResponse = Unirest.get("https://data.cityofnewyork.us/resource/2vyb-t2nz.json")
                    .header("accept", "application/json")
                    .queryString("$select", "latitude,longitude,ex_dwelling_unit")
                    .queryString("$where", "within_box(location,"
                            + Math.max(lat, lat1) + ","
                            + Math.min(lon, lon1) + ","
                            + Math.min(lat, lat1) + ","
                            + Math.max(lon, lon1) + ")")
                    .asJson();
            JSONArray buildingsRoot = jsonResponse.getBody().getArray();
            for (int i = 0; i < buildingsRoot.length(); i++) {
                JSONObject buildingObject = buildingsRoot.getJSONObject(i);
                if (buildingObject.keySet().size() == 3) {
                    Building building = new Building();
                    building.setLatitute(buildingObject.getDouble("latitude"));
                    building.setLongitude(buildingObject.getDouble("longitude"));
                    building.setOccupancy(buildingObject.getInt("ex_dwelling_unit"));
                    buildings.add(building);
                }
            }
        } catch (UnirestException e) {
            e.printStackTrace();
        }
        return buildings;
    }
}
