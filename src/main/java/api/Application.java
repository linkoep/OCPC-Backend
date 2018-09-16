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
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
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
        List<Building> buildings = getBuildingsNearLocation(lat, lon, bearing, boxes.size());
        Map<Coordinates, Building> result = new HashMap<>();
        for(int i = 0; i < boxes.size(); i++) {
            result.put(boxes.get(i), buildings.get(i));
        }
        try {
            return new ObjectMapper().writeValueAsString(result);
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

        //Initialize to 0s
        List<List<Integer>> colored = new ArrayList<>();
        for (int y = 0; y < foundBuilding.size(); y++) {
            colored.add(new ArrayList<>());
            for (int x = 0; x < foundBuilding.get(y).size(); x++) {
                colored.get(y).add(0);
            }
        }

        int label = 1;
        LinkedList<Point> queue = new LinkedList<>();
        for (int y = 0; y < foundBuilding.size(); y++) {
            for (int x = 0; x < foundBuilding.get(y).size(); x++) {
                if (foundBuilding.get(y).get(x)
                        && colored.get(y).get(x) == 0) {
                    colored.get(y).set(label, x);
                    queue.add(new Point(x, y));
                    while (!queue.isEmpty()) {
                        Point next = queue.pop();
                        int x1 = (int) next.getX();
                        int y1 = (int) next.getY();
                        if (y1 > 0) {
                            if(x1 > 0) {
                                if (colored.get(y1-1).get(x1-1) == 0) {
                                    colored.get(y1-1).set(label, x1-1);
                                    queue.add(new Point(x1-1, y1-1));
                                }
                            }
                            if(x < colored.get(y1-1).size()-1) {
                                if (colored.get(y1-1).get(x1+1) == 0) {
                                    colored.get(y1-1).set(label, x1+1);
                                    queue.add(new Point(x1+1, y1-1));
                                }
                            }
                        }
                        if (y1 < colored.size()-1) {
                            if(x1 > 0) {
                                if (colored.get(y1+1).get(x1-1) == 0) {
                                    colored.get(y1+1).set(label, x1-1);
                                    queue.add(new Point(x1-1, y1+1));
                                }
                            }
                            if(x < colored.get(y1+1).size()-1) {
                                if (colored.get(y1+1).get(x1+1) == 0) {
                                    colored.get(y1+1).set(label, x1+1);
                                    queue.add(new Point(x1+1, y1+1));
                                }
                            }
                        }
                    }
                    label++;
                }
            }
        }
        int[] xmins = new int[label];
        int[] ymins = new int[label];
        int[] xmaxs = new int[label];
        int[] ymaxs = new int[label];
        for (int y = 0; y < colored.size(); y++) {
            for (int x = 0; x < colored.get(y).size(); x++) {
                int lbl = colored.get(y).get(x)-1;
                xmins[lbl] = Math.min(xmins[lbl], x);
                ymins[lbl] = Math.min(ymins[lbl], y);
                xmaxs[lbl] = Math.min(xmaxs[lbl], x);
                ymaxs[lbl] = Math.min(ymaxs[lbl], y);
            }
        }

        List<Coordinates> coords = new ArrayList<>();
        for(int i = 0; i < label; i++) {
            coords.add(new Coordinates(xmins[i], ymins[i], xmaxs[i], ymaxs[i]));
        }
        return coords;
    }

    public static boolean containsBuilding(VisualRecognition service, InputStream image) {
        List<ClassResult> classes = classifyImage(service, image).getClassifiers().get(0).getClasses();
        for (ClassResult result : classes) {
            if (result.getClassName().equals("Building")) {
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

    public static List<Building> getBuildingsNearLocation(double lat, double lon, double bearing, int numBuildings) {
        List<Building> buildings = new ArrayList<>();
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
        if (bearing > 315 || bearing < 45) {
            Collections.sort(buildings, Comparator.comparing(Building::getLatitute).thenComparing(Building::getLongitude));
        } else if (bearing < 135) {
            Collections.sort(buildings, Comparator.comparing(Building::getLongitude).thenComparing(Building::getLatitute).reversed());
        } else if (bearing < 225) {
            Collections.sort(buildings, Comparator.comparing(Building::getLatitute).reversed().thenComparing(Building::getLongitude).reversed());
        } else {
            Collections.sort(buildings, Comparator.comparing(Building::getLongitude).reversed().thenComparing(Building::getLatitute));
        }

        return buildings.subList(0, numBuildings);
    }
}
