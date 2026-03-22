package script.winnerCustomization.repository;

import java.util.List;

public interface Repository<T> {
    void initialize();
    List<T> findAll();
    void replaceAll(List<T> entities);
}
