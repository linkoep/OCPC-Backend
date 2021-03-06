package api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.watson.developer_cloud.service.security.IamOptions;
import com.ibm.watson.developer_cloud.visual_recognition.v3.VisualRecognition;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.*;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import model.Box;
import model.Building;
import model.Coordinates;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
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
    private static final int GRID_PIX = 500;

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
        System.out.println("Getting Buildings");
        List<Building> buildings = getBuildingsNearLocation(lat, lon, bearing, boxes.size());
        List<Box> result = new ArrayList<>();
        for (int i = 0; i < Math.min(boxes.size(), buildings.size()); i++) {
            result.add(new Box(boxes.get(i).getTopLeft(), boxes.get(i).getBottomRight(), buildings.get(i)));
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
            for (int y = 0; y < source.getHeight() - GRID_PIX; y += GRID_PIX) {
                chopped.add(new ArrayList<>());
                for (int x = 0; x < source.getWidth() - GRID_PIX; x += GRID_PIX) {
                    BufferedImage subImage = source.getSubimage(x, y, GRID_PIX, GRID_PIX);
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    ImageIO.write(subImage, "jpg", os);
                    InputStream is = new ByteArrayInputStream(os.toByteArray());
                    chopped.get(y / GRID_PIX).add(is);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return chopped;
    }

    public static List<Coordinates> classifyImage(VisualRecognition service, List<List<InputStream>> chopped) {
        List<List<Boolean>> foundBuilding = chopped.stream()
                .map(row ->
                        row.stream()
                                .map(image -> containsBuilding(service, image))
                                .collect(Collectors.toList())
                )
                .collect(Collectors.toList());

        foundBuilding = rotateCW(foundBuilding);

        for (List<Boolean> row : foundBuilding) {
            for (boolean i : row) {
                System.out.print(i ? "T " : "F ");
            }
            System.out.println();
        }

        List<List<Integer>> colored = extractBlobs(foundBuilding);
        int maxLabel = 0;
        for (List<Integer> row : colored) {
            for (int i : row) {
                System.out.print(i + " ");
                maxLabel = Math.max(i, maxLabel);
            }
            System.out.println();
        }
        int[] xmins = new int[maxLabel];
        int[] ymins = new int[maxLabel];
        Arrays.fill(xmins, colored.get(0).size());
        Arrays.fill(ymins, colored.size());
        int[] xmaxs = new int[maxLabel];
        int[] ymaxs = new int[maxLabel];
        for (int y = 0; y < colored.size(); y++) {
            for (int x = 0; x < colored.get(y).size(); x++) {
                int lbl = colored.get(y).get(x) - 1;
                if (lbl >= 0) {
                    xmins[lbl] = Math.min(xmins[lbl], x);
                    ymins[lbl] = Math.min(ymins[lbl], y);
                    xmaxs[lbl] = Math.max(xmaxs[lbl], x);
                    ymaxs[lbl] = Math.max(ymaxs[lbl], y);
                }
            }
        }
        System.out.println("Found mins and maxes");
        List<Coordinates> coords = new ArrayList<>();
        for (int i = 0; i < maxLabel; i++) {
            coords.add(new Coordinates(xmins[i] * GRID_PIX, ymins[i] * GRID_PIX,
                    xmaxs[i] * GRID_PIX, ymaxs[i] * GRID_PIX));
        }
        System.out.println("Returning coordinates");
        return coords;
    }

    public static boolean containsBuilding(VisualRecognition service, InputStream image) {
        List<ClassifierResult> classifiers = classifyImage(service, image).getClassifiers();
        if (!classifiers.isEmpty()) {
            List<ClassResult> classes = classifiers.get(0).getClasses();
            for (ClassResult result : classes) {
                if (result.getClassName().equalsIgnoreCase("building")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static List<List<Integer>> extractBlobs(List<List<Boolean>> foundBuilding) {
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
                if (colored.get(y).get(x) == 0
                        && foundBuilding.get(y).get(x)) {
                    colored.get(y).set(x, label);
                    queue.add(new Point(x, y));
                    while (!queue.isEmpty()) {
                        Point next = queue.pop();
                        int x1 = (int) next.getX();
                        int y1 = (int) next.getY();
                        if (y1 > 0) {
                            if (colored.get(y1 - 1).get(x1) == 0
                                    && foundBuilding.get(y1 - 1).get(x1)) {
                                colored.get(y1 - 1).set(x1, label);
                                queue.add(new Point(x1, y1 - 1));
                            }
                        }
                        if (y1 < colored.size() - 1) {
                            if (colored.get(y1 + 1).get(x1) == 0
                                    && foundBuilding.get(y1 + 1).get(x1)) {
                                colored.get(y1 + 1).set(x1, label);
                                queue.add(new Point(x1, y1 + 1));
                            }
                        }
                        if (x1 > 0) {
                            if (colored.get(y1).get(x1 - 1) == 0
                                    && foundBuilding.get(y1).get(x1 - 1)) {
                                colored.get(y1).set(x1 - 1, label);
                                queue.add(new Point(x1 - 1, y1));
                            }
                        }
                        if (x1 < colored.get(y1).size() - 1) {
                            if (colored.get(y1).get(x1 + 1) == 0
                                    && foundBuilding.get(y1).get(x1 + 1)) {
                                colored.get(y1).set(x1 + 1, label);
                                queue.add(new Point(x1 + 1, y1));
                            }
                        }
                    }
                    label++;
                }
            }
        }
        return colored;
    }

    public static ClassifiedImage classifyImage(VisualRecognition service, InputStream input) {
        ClassifyOptions classifyOptions = new ClassifyOptions.Builder()
                .imagesFile(input)
                .imagesFilename("test.jpg")
                .threshold((float) 0.3)
                .classifierIds(Arrays.asList("default"))
                .build();
        ClassifiedImages result = service.classify(classifyOptions).execute();
        return result.getImages().get(0);
    }

    public static List<Building> getBuildingsNearLocation(double lat, double lon, double bearing, int numBuildings) {
        List<Building> buildings = new ArrayList<>();
        //For demo only
        if (lat >= 42.5 && lat <= 43 && lon >= -73.9 && lon <= -73.3) {
            Building b = new Building();
            b.setLatitute(42.7);
            b.setLongitude(-73.6);
            b.setOccupancy(4780);
            buildings.add(b);
            return buildings;
        }
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

        return buildings.subList(0, Math.min(numBuildings, buildings.size()));
    }

    static List<List<Boolean>> rotateCW(List<List<Boolean>> mat) {
        final int M = mat.size();
        final int N = mat.get(0).size();
        List<List<Boolean>> ret = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            ret.add(new ArrayList<>(Collections.nCopies(M, false)));
        }
        for (int r = 0; r < M; r++) {
            for (int c = 0; c < N; c++) {
                ret.get(c).set(M - 1 - r, mat.get(r).get(c));
            }
        }
        return ret;
    }
}
