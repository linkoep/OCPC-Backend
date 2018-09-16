import api.Application;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class HttpTest {
    @Test
    public void testHttpRequest() {
        Application app = new Application();
        assertThat(app.getBuildingsNearLocation(40.680692,-73.988398,0), is(not(nullValue())));
        assertThat(app.getBuildingsNearLocation(40.680692,-73.988398,90), is(not(nullValue())));
        assertThat(app.getBuildingsNearLocation(40.680692,-73.988398,180), is(not(nullValue())));
        assertThat(app.getBuildingsNearLocation(40.680692,-73.988398,270), is(not(nullValue())));
    }

}
