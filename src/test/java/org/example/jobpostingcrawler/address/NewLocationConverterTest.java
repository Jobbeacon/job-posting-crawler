package org.example.jobpostingcrawler.address;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NewLocationConverterTest {

    private static NewLocationConverter converter;

    @BeforeAll
    static void setUp() {
        converter = new NewLocationConverter();
    }

    @Test
    void writesCanonicalWardWhenTheNameIsAlreadyNew() {
        assertEquals(
                "SSI Tower, 1 Châu Văn Liêm, Phường Từ Liêm, Thành phố Hà Nội",
                converter.toNewAddress("SSI Tower, 1 Châu Văn Liêm, Tu Liem, Ha Noi")
        );
        assertEquals(
                "9 Dinh Tien Hoang, Phường Tân Định, Thành phố Hồ Chí Minh",
                converter.toNewAddress("9 Dinh Tien Hoang, Tan Dinh Ward, Ho Chi Minh")
        );
        assertEquals(
                "Sailing Tower, 111A Pasteur, Phường Sài Gòn, Thành phố Hồ Chí Minh",
                converter.toNewAddress("Sailing Tower, 111A Pasteur, Ward Sai Gon, Ho Chi Minh")
        );
        assertEquals(
                "The Hallmark, 15 Tran Bach Dang, Phường An Khánh, Thành phố Hồ Chí Minh",
                converter.toNewAddress("The Hallmark, 15 Tran Bach Dang, An Khanh Ward, An Khanh, Ho Chi Minh")
        );
    }

    @Test
    void convertsAnOldWardThatMapsToOneNewWard() {
        assertEquals(
                "Số 6 Quang Trung, Phường Cửa Nam, Thành phố Hà Nội",
                converter.toNewAddress("Số 6 Quang Trung, phường Trần Hưng Đạo, Ha Noi")
        );
        assertEquals(
                "Tầng 20A, Vincom Center Đồng Khởi, 72 Lê Thánh Tôn, Phường Sài Gòn, Thành phố Hồ Chí Minh",
                converter.toNewAddress("Tầng 20A, Vincom Center Đồng Khởi, 72 Lê Thánh Tôn, phường Bến Nghé, Ho Chi Minh")
        );
        assertEquals(
                "9 Ton Duc Thang, Phường Sài Gòn, Thành phố Hồ Chí Minh",
                converter.toNewAddress("9 Ton Duc Thang, Ben Nghe Ward, District 1, HCM, Ho Chi Minh")
        );
    }

    @Test
    void leavesNewLocationEmptyWhenTheWardCannotBeChosen() {
        assertNull(converter.toNewAddress("Tòa nhà MB, số 18 Lê Văn Lương, Phường Yên Hòa, Ha Noi"));
        assertNull(converter.toNewAddress("106 Nguyen Van Troi, Phu Nhuan, Ho Chi Minh"));
        assertNull(converter.toNewAddress("Epic tower, Lane 19 Duy Tan street, Cau Giay, Ha Noi"));
        assertNull(converter.toNewAddress("Tầng 15, tòa Keangnam Landmark 72, Mễ Trì, Yen Hoa, Ha Noi"));
        assertNull(converter.toNewAddress("City, Ha Noi"));
        assertNull(converter.toNewAddress(null));
    }
}
