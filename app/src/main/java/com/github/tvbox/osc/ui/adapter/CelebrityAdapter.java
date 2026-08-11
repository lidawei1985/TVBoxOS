package com.github.tvbox.osc.ui.adapter;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.search.Celebrity;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

/**
 * 规格3：明星联想卡片适配器。
 * 卡片 = 头像区(海报图 或 首字头像) + 姓名。点击由 Activity 处理（search 该明星）。
 */
public class CelebrityAdapter extends BaseQuickAdapter<Celebrity, BaseViewHolder> {
    public CelebrityAdapter() {
        super(R.layout.item_search_celebrity, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Celebrity item) {
        helper.setText(R.id.tvName, item.getName());
        String initial = (item.getName() != null && item.getName().length() > 0)
                ? String.valueOf(item.getName().charAt(0)) : "";
        helper.setText(R.id.tvAvatar, initial);

        android.widget.ImageView iv = helper.getView(R.id.ivPoster);
        String poster = item.getPoster();
        if (poster != null && !poster.isEmpty()) {
            try {
                Picasso.with(helper.itemView.getContext()).load(poster).into(iv);
            } catch (Throwable t) {
                iv.setImageDrawable(null);
            }
        } else {
            iv.setImageDrawable(null);
        }
    }
}
