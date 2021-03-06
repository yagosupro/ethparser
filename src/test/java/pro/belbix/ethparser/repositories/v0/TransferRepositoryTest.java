package pro.belbix.ethparser.repositories.v0;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import pro.belbix.ethparser.Application;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
public class TransferRepositoryTest {

    @Autowired
    private TransferRepository transferRepository;

    @Test
    public void fetchAllByOwnerAndRecipient() {
        assertNotNull(transferRepository.fetchAllByOwnerAndRecipient(
            "0xf00dd244228f51547f0563e60bca65a30fbf5f7f",
            "0xc97ddaa8091abaf79a4910b094830cce5cdd78f4", 0, Long.MAX_VALUE
        ));
    }

    @Test
    public void getBalanceForOwner() {
        assertNotNull(transferRepository.getBalanceForOwner(
            "0xf00dd244228f51547f0563e60bca65a30fbf5f7f", Long.MAX_VALUE));
    }

    @Test
    public void fetchAllFromBlockDate() {
        assertNotNull(transferRepository.fetchAllFromBlockDate(0));
    }

    @Test
    public void fetchAllWithoutMethods() {
        assertNotNull(transferRepository.fetchAllWithoutMethods());
    }

    @Test
    public void fetchAllWithoutPrice() {
        assertNotNull(transferRepository.fetchAllWithoutPrice());
    }

    @Test
    public void fetchAllWithoutProfits() {
        assertNotNull(transferRepository.fetchAllWithoutProfits());
    }
}
