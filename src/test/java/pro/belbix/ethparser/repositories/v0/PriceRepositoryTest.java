package pro.belbix.ethparser.repositories.v0;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import pro.belbix.ethparser.Application;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
public class PriceRepositoryTest {
    private final Pageable limitOne = PageRequest.of(0, 1);

    @Autowired
    private PriceRepository priceRepository;

    @Test
    public void fetchLastPrice() {
        assertNotNull(priceRepository.fetchLastPrice("UNI_LP_ETH_USDT", Long.MAX_VALUE, limitOne));
    }

    @Test
    public void fetchLastPrices() {
        assertNotNull(priceRepository.fetchLastPrices());
    }
}
